package com.rakib.docscanner

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.googlecode.tesseract.android.TessBaseAPI
import com.rakib.docscanner.databinding.ActivitySessionBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "document in progress" screen: every page confirmed in ReviewActivity
 * lands here. From here the user can add more pages (batch scan), reorder
 * by dragging, rotate or delete a page, and finally export the whole thing
 * as one multi-page PDF — optionally with a Phase 5 OCR text layer.
 */
class SessionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySessionBinding
    private lateinit var adapter: PageAdapter
    private var progressDialog: AlertDialog? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val intent = Intent(this, ReviewActivity::class.java).apply {
                putExtra(ReviewActivity.EXTRA_IMAGE_URI, uri.toString())
            }
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = PageAdapter(
            onRotate = { item -> PageSession.rotatePage(item.id, 90); refreshList() },
            onDelete = { item -> PageSession.removePage(item.id); refreshList() },
            onReorder = { from, to -> PageSession.movePage(from, to) }
        )
        binding.recyclerPages.layoutManager = LinearLayoutManager(this)
        binding.recyclerPages.adapter = adapter
        ItemTouchHelper(PageTouchHelperCallback(adapter)).attachToRecyclerView(binding.recyclerPages)

        binding.btnAddPage.setOnClickListener { showAddPageChooser() }
        binding.btnExportSession.setOnClickListener { showExportDialog() }
        binding.btnRetypeset.setOnClickListener { showRetypesetDialog() }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val pages = PageSession.getPages()
        adapter.submitList(pages)
        binding.tvEmpty.visibility = if (pages.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        binding.recyclerPages.visibility = if (pages.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.btnExportSession.isEnabled = pages.isNotEmpty()
        binding.btnRetypeset.isEnabled = pages.isNotEmpty()
    }

    private fun showAddPageChooser() {
        AlertDialog.Builder(this)
            .setTitle("Add page from")
            .setItems(arrayOf("Camera", "Gallery")) { _, which ->
                if (which == 0) {
                    startActivity(Intent(this, ScanActivity::class.java))
                } else {
                    pickImage.launch("image/*")
                }
            }
            .show()
    }

    private fun showExportDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_export, null)
        val nameField = dialogView.findViewById<EditText>(R.id.etDocumentName)
        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.radioQuality)
        val ocrCheck = dialogView.findViewById<CheckBox>(R.id.checkOcr)
        val englishCheck = dialogView.findViewById<CheckBox>(R.id.checkEnglishOnly)

        AlertDialog.Builder(this)
            .setTitle("Export ${PageSession.pageCount()}-page PDF")
            .setView(dialogView)
            .setPositiveButton("Export") { _, _ ->
                val quality = if (radioGroup.checkedRadioButtonId == R.id.radioCompressed) {
                    PdfExporter.Quality.COMPRESSED
                } else {
                    PdfExporter.Quality.STANDARD
                }
                doExport(nameField.text?.toString(), quality, ocrCheck.isChecked, englishCheck.isChecked)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Creates the OCR engine the person actually asked for: PP-OCRv5
     * (English-only, generally more accurate on photographed pages) when
     * they checked "This document is fully English", Tesseract
     * (Bangla+English) otherwise. Returns null if that engine's init()
     * fails, so callers fall back the same way regardless of which engine
     * was requested — a failed PaddleOCR init doesn't need different
     * handling than a failed Tesseract init.
     */
    private fun createOcrEngine(useEnglishEngine: Boolean): TextOcrEngine? {
        return if (useEnglishEngine) {
            val engine = PaddleOcrEngine(this)
            if (engine.init()) engine else null
        } else {
            val engine = OcrEngine(this)
            if (engine.init()) engine else null
        }
    }

    private fun closeOcrEngine(engine: TextOcrEngine) {
        when (engine) {
            is OcrEngine -> engine.close()
            is PaddleOcrEngine -> engine.close()
        }
    }

    private fun doExport(name: String?, quality: PdfExporter.Quality, runOcr: Boolean, useEnglishEngine: Boolean) {
        val pages = PageSession.getPages()
        if (pages.isEmpty()) return

        binding.btnExportSession.isEnabled = false
        if (runOcr) {
            showProgress(if (useEnglishEngine) "Running OCR (English)…" else "Running OCR (Bangla + English)…")
        } else {
            showProgress("Building PDF…")
        }

        CoroutineScope(Dispatchers.Main).launch {
            val bitmaps = withContext(Dispatchers.Default) { pages.map { PageSession.loadBitmap(it) } }

            var recognizedText: String? = null
            val file = withContext(Dispatchers.Default) {
                if (runOcr) {
                    val engine = createOcrEngine(useEnglishEngine)
                    if (engine == null) {
                        // OCR setup failed (e.g. language/model data
                        // missing) — fall back to a normal export rather
                        // than losing the scan the user is trying to save.
                        return@withContext PdfExporter.exportMultiPage(this@SessionActivity, bitmaps, quality, name)
                    }
                    try {
                        val ocrResults = bitmaps.map { engine.recognize(it, TessBaseAPI.PageSegMode.PSM_AUTO) }
                        recognizedText = ocrResults.joinToString("\n\n--- Page Break ---\n\n") { it.fullText }
                        PdfExporter.exportSearchablePdf(
                            this@SessionActivity,
                            bitmaps.zip(ocrResults),
                            quality,
                            name
                        )
                    } finally {
                        closeOcrEngine(engine)
                    }
                } else {
                    PdfExporter.exportMultiPage(this@SessionActivity, bitmaps, quality, name)
                }
            }

            recognizedText?.let { text ->
                withContext(Dispatchers.Default) { PdfExporter.exportTextFile(this@SessionActivity, file, text) }
            }

            PageSession.clear()
            hideProgress()

            if (runOcr && recognizedText == null) {
                Toast.makeText(
                    this@SessionActivity,
                    "OCR setup failed — saved as a normal (non-searchable) PDF instead",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this@SessionActivity, "Saved: ${file.name}", Toast.LENGTH_LONG).show()
            }

            if (recognizedText != null) {
                showExtractedTextDialog(recognizedText!!) { shareAndReturnHome(file) }
            } else {
                shareAndReturnHome(file)
            }
        }
    }

    private fun showExtractedTextDialog(text: String, onDismiss: () -> Unit) {
        val textView = TextView(this).apply {
            setPadding(48, 32, 48, 32)
            setText(text)
            setTextIsSelectable(true)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(textView) }

        AlertDialog.Builder(this)
            .setTitle("Extracted Text")
            .setView(scroll)
            .setPositiveButton("Copy") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Scanned text", text))
                Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                onDismiss()
            }
            .setNegativeButton("Close") { _, _ -> onDismiss() }
            .setOnCancelListener { onDismiss() }
            .show()
    }

    private fun shareAndReturnHome(file: java.io.File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share document"))

        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(mainIntent)
        finish()
    }

    private fun showRetypesetDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_retypeset, null)
        val nameField = dialogView.findViewById<EditText>(R.id.etRetypesetName)
        val radioGroup = dialogView.findViewById<RadioGroup>(R.id.radioRetypesetStyle)
        val englishCheck = dialogView.findViewById<CheckBox>(R.id.checkEnglishOnly)

        AlertDialog.Builder(this)
            .setTitle("Digital Re-typeset")
            .setView(dialogView)
            .setPositiveButton("Create") { _, _ ->
                val style = when (radioGroup.checkedRadioButtonId) {
                    R.id.radioStyleBook -> RetypesetStyle.BOOK
                    R.id.radioStyleNotes -> RetypesetStyle.NOTES
                    else -> RetypesetStyle.PLAIN
                }
                doRetypeset(nameField.text?.toString(), style, englishCheck.isChecked)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doRetypeset(name: String?, style: RetypesetStyle, useEnglishEngine: Boolean) {
        val pages = PageSession.getPages()
        if (pages.isEmpty()) return

        binding.btnRetypeset.isEnabled = false
        showProgress("Analyzing layout and running OCR (${style.name})…")

        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.Default) {
                val bitmaps = pages.map { PageSession.loadBitmap(it) }
                val engine = createOcrEngine(useEnglishEngine) ?: return@withContext RetypesetOutcome.OcrFailed

                // Phase 6: PP-DocLayout-S finds titles/paragraphs/tables/
                // images before OCR runs, so the retypeset can treat each
                // differently instead of forcing everything through the
                // plain-paragraph pipeline. If the model asset is missing
                // or ONNX Runtime fails to start on this device,
                // layoutEngine is left null and DocumentStructureEngine
                // falls back to exactly Phase 5.5's old behaviour
                // (flat full-page OCR, everything as a paragraph) —
                // silently, not as an error, since the document is still
                // produced either way.
                val layoutEngine = LayoutEngine(this@SessionActivity)
                val layoutAvailable = layoutEngine.init()

                val structured = try {
                    DocumentStructureEngine.buildDocument(
                        context = this@SessionActivity,
                        pages = bitmaps,
                        layoutEngine = if (layoutAvailable) layoutEngine else null,
                        ocrEngine = engine
                    )
                } finally {
                    closeOcrEngine(engine)
                    if (layoutAvailable) layoutEngine.close()
                    TableStructureEngine.releaseModel()
                }

                if (structured.blocks.isEmpty()) return@withContext RetypesetOutcome.NoText

                // Fixed reference resolution/density, not the phone's own
                // display metrics — so a re-typeset document looks the same
                // whichever device generated or opens it, rather than the
                // font-to-page proportions shifting with screen density.
                val retypesetPages = RetypesetRenderer.renderDocument(
                    blocks = structured.blocks,
                    style = style,
                    pageWidthPx = RETYPESET_PAGE_WIDTH_PX,
                    pageHeightPx = RETYPESET_PAGE_HEIGHT_PX,
                    density = RETYPESET_DENSITY,
                    headerText = structured.headerText,
                    footerText = structured.footerText
                )
                val file = PdfExporter.exportMultiPage(
                    this@SessionActivity, retypesetPages, PdfExporter.Quality.STANDARD, name
                )
                RetypesetOutcome.Success(file, layoutAvailable)
            }

            hideProgress()
            binding.btnRetypeset.isEnabled = true

            when (result) {
                is RetypesetOutcome.OcrFailed -> {
                    Toast.makeText(this@SessionActivity, "OCR setup failed — could not re-typeset", Toast.LENGTH_LONG).show()
                }
                is RetypesetOutcome.NoText -> {
                    Toast.makeText(this@SessionActivity, "No readable text was found on these pages", Toast.LENGTH_LONG).show()
                }
                is RetypesetOutcome.Success -> {
                    PageSession.clear()
                    val message = if (result.layoutAware) {
                        "Saved (layout-aware): ${result.file.name}"
                    } else {
                        "Saved (plain text — layout model unavailable): ${result.file.name}"
                    }
                    Toast.makeText(this@SessionActivity, message, Toast.LENGTH_LONG).show()
                    shareAndReturnHome(result.file)
                }
            }
        }
    }

    private sealed class RetypesetOutcome {
        object OcrFailed : RetypesetOutcome()
        object NoText : RetypesetOutcome()
        data class Success(val file: java.io.File, val layoutAware: Boolean) : RetypesetOutcome()
    }

    private fun showProgress(message: String) {
        progressDialog = AlertDialog.Builder(this)
            .setMessage(message)
            .setCancelable(false)
            .show()
    }

    private fun hideProgress() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    companion object {
        // A4-proportioned reference page at a fixed virtual density, used
        // only for Digital Re-typeset output — deliberately not tied to the
        // generating phone's actual screen density (see doRetypeset()).
        private const val RETYPESET_PAGE_WIDTH_PX = 1240
        private const val RETYPESET_PAGE_HEIGHT_PX = 1754
        private const val RETYPESET_DENSITY = 2f
    }
}
