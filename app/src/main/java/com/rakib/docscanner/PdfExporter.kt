package com.rakib.docscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Phase 1: single/multi-page export at full quality.
 * Phase 4: multi-page assembly, a real compress option, rename.
 * Phase 5: an optional searchable text layer (invisible OCR'd text placed
 * over each word's position) on top of any of the above.
 */
object PdfExporter {

    enum class Quality { STANDARD, COMPRESSED }

    fun exportSinglePage(context: Context, bitmap: Bitmap, name: String? = null): File {
        return exportMultiPage(context, listOf(bitmap), Quality.STANDARD, name)
    }

    /**
     * Builds one PDF from an ordered list of page bitmaps.
     *
     * Quality.STANDARD draws each bitmap at full resolution, losslessly,
     * straight into the PDF page — the "don't worry about size" mode.
     *
     * Quality.COMPRESSED re-encodes each page as JPEG at a moderate quality
     * before embedding it. This is a real, meaningful size reduction (not
     * a placeholder) for when Rakib or a user explicitly wants a smaller
     * file to share — it's opt-in, never the default.
     */
    fun exportMultiPage(
        context: Context,
        pages: List<Bitmap>,
        quality: Quality = Quality.STANDARD,
        name: String? = null
    ): File {
        require(pages.isNotEmpty()) { "Cannot export a PDF with zero pages" }
        return buildPdf(context, name) { document ->
            for ((index, bitmap) in pages.withIndex()) {
                val pageBitmap = if (quality == Quality.COMPRESSED) reencodeJpeg(bitmap, 60) else bitmap
                val pageInfo = PdfDocument.PageInfo
                    .Builder(pageBitmap.width, pageBitmap.height, index + 1)
                    .create()
                val page = document.startPage(pageInfo)
                page.canvas.drawBitmap(pageBitmap, 0f, 0f, null)
                document.finishPage(page)
            }
        }
    }

    /**
     * Same as exportMultiPage, but also embeds an invisible text layer at
     * each OCR'd word's position — the standard "searchable scanned PDF"
     * technique: the image is what's visible, but a PDF reader's
     * search/select/copy tools see real text lined up underneath it.
     *
     * Word vertical position is approximated from Tesseract's word bounding
     * box (its bottom edge, treated as the text baseline) rather than a
     * true font baseline — Tesseract doesn't report one separately. In
     * practice this lines up closely enough for search/select/copy to work
     * correctly; it's not pixel-perfect text rendering, which doesn't
     * matter since the text is never actually seen.
     */
    fun exportSearchablePdf(
        context: Context,
        pages: List<Pair<Bitmap, OcrPageResult>>,
        quality: Quality = Quality.STANDARD,
        name: String? = null
    ): File {
        require(pages.isNotEmpty()) { "Cannot export a PDF with zero pages" }
        val invisibleTextPaint = Paint().apply {
            alpha = 0 // present in the PDF's content stream for search/copy, never rendered visibly
            isAntiAlias = true
        }

        return buildPdf(context, name) { document ->
            for ((index, pair) in pages.withIndex()) {
                val (bitmap, ocr) = pair
                val pageBitmap = if (quality == Quality.COMPRESSED) reencodeJpeg(bitmap, 60) else bitmap
                val scale = pageBitmap.width.toFloat() / bitmap.width.toFloat()

                val pageInfo = PdfDocument.PageInfo
                    .Builder(pageBitmap.width, pageBitmap.height, index + 1)
                    .create()
                val page = document.startPage(pageInfo)
                page.canvas.drawBitmap(pageBitmap, 0f, 0f, null)

                for (word in ocr.words) {
                    val boxHeight = (word.bottom - word.top) * scale
                    if (boxHeight <= 0f) continue
                    invisibleTextPaint.textSize = boxHeight
                    page.canvas.drawText(
                        word.text,
                        word.left * scale,
                        word.bottom * scale,
                        invisibleTextPaint
                    )
                }

                document.finishPage(page)
            }
        }
    }

    /** Re-encodes a bitmap through JPEG at [jpegQuality] to force real size reduction. */
    private fun reencodeJpeg(bitmap: Bitmap, jpegQuality: Int): Bitmap {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, stream)
        val bytes = stream.toByteArray()
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** Shared document-creation, naming, and file-writing logic for both export paths. */
    private fun buildPdf(context: Context, name: String?, fillPages: (PdfDocument) -> Unit): File {
        val document = PdfDocument()
        fillPages(document)

        val safeName = sanitizeFileName(name)
        val fileName = if (safeName.isNullOrBlank()) {
            "Scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(System.currentTimeMillis()) + ".pdf"
        } else {
            "$safeName.pdf"
        }

        val outDir = File(context.getExternalFilesDir(null), "DocScanner")
        if (!outDir.exists()) outDir.mkdirs()
        var outFile = File(outDir, fileName)
        // Don't silently overwrite a same-named existing document.
        var suffix = 1
        while (outFile.exists()) {
            outFile = File(outDir, "${fileName.removeSuffix(".pdf")}_$suffix.pdf")
            suffix++
        }

        FileOutputStream(outFile).use { document.writeTo(it) }
        document.close()
        return outFile
    }

    private fun sanitizeFileName(name: String?): String? {
        if (name.isNullOrBlank()) return null
        // Only strip characters that are actually unsafe in a filename.
        // The previous ASCII-only version would have silently blanked out
        // any Bangla document name, which defeats the point of letting
        // Rakib's users name documents in their own language.
        return name.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_").take(60)
    }

    /** Lists previously exported PDFs for the "My Documents" screen, newest first. */
    fun listExportedDocuments(context: Context): List<File> {
        val outDir = File(context.getExternalFilesDir(null), "DocScanner")
        if (!outDir.exists()) return emptyList()
        return outDir.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Writes the concatenated OCR text of a document alongside its PDF (same base name, .txt). */
    fun exportTextFile(context: Context, baseFile: File, fullText: String): File {
        val txtFile = File(baseFile.parentFile, baseFile.nameWithoutExtension + ".txt")
        txtFile.writeText(fullText)
        return txtFile
    }
}
