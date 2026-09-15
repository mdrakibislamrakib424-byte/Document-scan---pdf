package com.rakib.docscanner

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.rakib.docscanner.databinding.ActivityReviewBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-page pipeline screen:
 *   1. Show the source photo with auto-detected corners (draggable).
 *   2. On "Confirm Crop": perspective-warp + curve-dewarp to those corners.
 *   3. Let the user flip between Original / Grayscale / B&W / AI Enhance
 *      (with an intensity slider) and optionally upscale.
 *   4. "Add Page" saves the result into the current PageSession and hands
 *      off to SessionActivity, where multiple pages get assembled into one
 *      multi-page PDF (Phase 4).
 */
class ReviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_IMAGE_URI = "extra_image_uri"
    }

    private lateinit var binding: ActivityReviewBinding
    private lateinit var sourceBitmap: Bitmap
    private var processedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra(EXTRA_IMAGE_URI)
        if (uriString == null) {
            finish()
            return
        }

        binding.btnConfirm.setOnClickListener { onConfirmCrop() }
        binding.btnModeOriginal.setOnClickListener { showMode(Mode.ORIGINAL) }
        binding.btnModeGray.setOnClickListener { showMode(Mode.GRAYSCALE) }
        binding.btnModeBW.setOnClickListener { showMode(Mode.BLACK_AND_WHITE) }
        binding.btnModeEnhance.setOnClickListener { showMode(Mode.AI_ENHANCE) }
        binding.btnUpscale.setOnClickListener { applyUpscale() }
        binding.btnExport.setOnClickListener { addPageToSession() }

        binding.seekIntensity.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                // Re-run Enhance at the new intensity only if Enhance is the
                // mode currently on screen — moving the slider shouldn't
                // silently switch you out of Original/Gray/B&W.
                if (currentMode == Mode.AI_ENHANCE) showMode(Mode.AI_ENHANCE)
            }
        })

        loadAndDetect(Uri.parse(uriString))
    }

    private fun loadAndDetect(uri: Uri) {
        CoroutineScope(Dispatchers.Main).launch {
            sourceBitmap = withContext(Dispatchers.Default) {
                BitmapUtils.loadOrientedBitmap(this@ReviewActivity, uri)
            }
            binding.imageView.setImageBitmap(sourceBitmap)

            val detected = withContext(Dispatchers.Default) {
                ImageProcessor.detectDocumentCorners(sourceBitmap)
            }
            val quality = withContext(Dispatchers.Default) {
                ScanQualityAnalyzer.analyzeBitmap(sourceBitmap)
            }
            binding.tvQuality.text = "Scan Quality: ${quality.score}% — ${quality.message}"

            binding.imageView.post {
                val bitmapCorners = detected ?: defaultCorners(sourceBitmap)
                binding.overlayView.corners = bitmapCorners.map { toViewCoords(it) }.toTypedArray()
            }
        }
    }

    /** 8%-inset rectangle used when auto-detection doesn't find a confident page. */
    private fun defaultCorners(bitmap: Bitmap): Array<PointF> {
        val marginX = bitmap.width * 0.08f
        val marginY = bitmap.height * 0.08f
        return arrayOf(
            PointF(marginX, marginY),
            PointF(bitmap.width - marginX, marginY),
            PointF(bitmap.width - marginX, bitmap.height - marginY),
            PointF(marginX, bitmap.height - marginY)
        )
    }

    // ---- Coordinate mapping between the source bitmap and the fitCenter ImageView ----

    private fun imageDisplayRect(): android.graphics.RectF {
        val viewWidth = binding.imageView.width.toFloat()
        val viewHeight = binding.imageView.height.toFloat()
        val bmpWidth = sourceBitmap.width.toFloat()
        val bmpHeight = sourceBitmap.height.toFloat()

        val scale = minOf(viewWidth / bmpWidth, viewHeight / bmpHeight)
        val displayWidth = bmpWidth * scale
        val displayHeight = bmpHeight * scale
        val left = (viewWidth - displayWidth) / 2f
        val top = (viewHeight - displayHeight) / 2f
        return android.graphics.RectF(left, top, left + displayWidth, top + displayHeight)
    }

    private fun toViewCoords(bitmapPoint: PointF): PointF {
        val rect = imageDisplayRect()
        val scale = rect.width() / sourceBitmap.width
        return PointF(rect.left + bitmapPoint.x * scale, rect.top + bitmapPoint.y * scale)
    }

    private fun toBitmapCoords(viewPoint: PointF): PointF {
        val rect = imageDisplayRect()
        val scale = sourceBitmap.width / rect.width()
        return PointF((viewPoint.x - rect.left) * scale, (viewPoint.y - rect.top) * scale)
    }

    // ---- Pipeline actions ----

    private fun onConfirmCrop() {
        val bitmapCorners = binding.overlayView.corners.map { toBitmapCoords(it) }.toTypedArray()

        binding.btnConfirm.isEnabled = false
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.Default) {
                val flat = ImageProcessor.warpPerspective(sourceBitmap, bitmapCorners)
                ImageProcessor.dewarpCurved(flat)
            }
            processedBitmap = result
            binding.overlayView.visibility = android.view.View.GONE
            binding.tvQuality.visibility = android.view.View.GONE
            binding.modeToggleRow.visibility = android.view.View.VISIBLE
            binding.intensityRow.visibility = android.view.View.VISIBLE
            binding.enhanceToolsRow.visibility = android.view.View.VISIBLE
            binding.btnExport.visibility = android.view.View.VISIBLE
            binding.btnConfirm.visibility = android.view.View.GONE
            binding.imageView.setImageBitmap(result)
        }
    }

    private enum class Mode { ORIGINAL, GRAYSCALE, BLACK_AND_WHITE, AI_ENHANCE }
    private var currentMode: Mode = Mode.ORIGINAL

    private fun showMode(mode: Mode) {
        val base = processedBitmap ?: return
        currentMode = mode
        // Slider range 0-100 maps to intensity 0.0-2.0; the default
        // progress (50) lands exactly on the balanced 1.0 default.
        val intensity = binding.seekIntensity.progress / 50.0
        CoroutineScope(Dispatchers.Main).launch {
            val out = withContext(Dispatchers.Default) {
                when (mode) {
                    Mode.ORIGINAL -> base
                    Mode.GRAYSCALE -> ImageProcessor.toGrayscale(base)
                    Mode.BLACK_AND_WHITE -> ImageProcessor.toBlackAndWhite(base)
                    Mode.AI_ENHANCE -> Phase2Processor.enhanceFull(base, intensity)
                }
            }
            binding.imageView.setImageBitmap(out)
            currentDisplayed = out
        }
    }

    private var currentDisplayed: Bitmap? = null

    /** Applies on top of whatever is currently displayed (stacks with a mode, e.g. Enhance -> Upscale). */
    private fun applyUpscale() {
        val base = currentDisplayed ?: processedBitmap ?: return
        binding.btnUpscale.isEnabled = false
        CoroutineScope(Dispatchers.Main).launch {
            val out = withContext(Dispatchers.Default) {
                Phase2Processor.upscale(base, factor = 2.0)
            }
            binding.imageView.setImageBitmap(out)
            currentDisplayed = out
            binding.btnUpscale.isEnabled = true
            binding.btnUpscale.text = "Upscale 2x ✓"
        }
    }

    private fun addPageToSession() {
        val toAdd = currentDisplayed ?: processedBitmap ?: return
        binding.btnExport.isEnabled = false
        CoroutineScope(Dispatchers.Main).launch {
            withContext(Dispatchers.Default) {
                PageSession.addPage(this@ReviewActivity, toAdd)
            }
            val intent = Intent(this@ReviewActivity, SessionActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            startActivity(intent)
            finish()
        }
    }
}
