package com.rakib.docscanner

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

data class OcrWord(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Float
)

data class OcrPageResult(
    val fullText: String,
    val words: List<OcrWord>,
    val paragraphs: List<String> = emptyList(),
    // Phase 6: line-level text, needed for table regions where paragraph
    // reflow would destroy the row structure that made it a table.
    val lines: List<String> = emptyList(),
    // Phase 6.1: the same lines, but keeping each word's own bounding box
    // instead of collapsing it to text — TableStructureRecovery needs the
    // geometry to find columns, not just rows.
    val lineWords: List<List<OcrWord>> = emptyList()
)

/**
 * Wraps Tesseract (via Tesseract4Android) for Bangla + English recognition.
 *
 * Why Tesseract and not PaddleOCR: verified directly — PaddleOCR's
 * mobile-deployable models (PP-OCRv6 and earlier) cover Chinese, English,
 * and Latin-script languages only; Bengali script isn't in that list.
 * PaddleOCR's newer model that does cover Bengali (PaddleOCR-VL) is a
 * 0.9-billion-parameter vision-language model — not something a phone can
 * run. Tesseract's `ben.traineddata` is a real, working, offline option
 * that has existed for years.
 *
 * One engine instance is expensive to create (loads both language models
 * into memory), so this class is meant to be created once per OCR batch
 * (e.g. once per multi-page export) and reused across pages, then closed.
 */
class OcrEngine(private val context: Context) : TextOcrEngine {

    private var tess: TessBaseAPI? = null

    /** Copies tessdata from assets to a real filesystem path (Tesseract requires this, it can't read language files straight out of the compressed APK) and initializes the engine. Call once before recognize(). */
    fun init(): Boolean {
        val dataDir = ensureTessDataExtracted(context)
        val api = TessBaseAPI()
        val ok = api.init(dataDir.absolutePath, "ben+eng")
        if (ok) tess = api
        return ok
    }

    /**
     * Runs OCR on one page (or, from Phase 6 on, a cropped region of a
     * page — the layout engine hands this class one bitmap per detected
     * region rather than one per full photo). init() must have succeeded
     * first.
     *
     * [pageSegMode] defaults to Tesseract's own automatic segmentation,
     * which is right for a full page. When OCR'ing a *region* that the
     * layout model has already told us is one uniform block (an ordinary
     * paragraph, or — importantly — a table, where PSM_AUTO's own column
     * detection would fight the layout model's), passing
     * `PSM_SINGLE_BLOCK` is more reliable: Tesseract is told "don't
     * re-discover structure, this crop already *is* one block", so it
     * spends its effort on recognition instead of second-guessing the
     * layout.
     *
     * Typed as plain `Int`, not `TessBaseAPI.PageSegMode` — that nested
     * class isn't an enum/instantiable type, it's just a holder of
     * `public static final int` constants (Android's `@IntDef` pattern,
     * confirmed against the library's actual source). `PSM_AUTO` and
     * `PSM_SINGLE_BLOCK` are themselves `Int` values, so that's the type
     * a caller can actually pass.
     */
    override fun recognize(
        bitmap: Bitmap,
        pageSegMode: Int
    ): OcrPageResult {
        val api = tess ?: throw IllegalStateException("OcrEngine.init() was not called or failed")
        // setPageSegMode(Int) is a plain method call, not property syntax
        // — TessBaseAPI is a Java class, and even if it had a getter too,
        // Kotlin only synthesizes `api.pageSegMode = x` when both a getter
        // and setter exist; here it doesn't matter since the param is a
        // plain Int, not an object needing that shorthand anyway.
        api.setPageSegMode(pageSegMode)
        api.setImage(bitmap)
        val text = api.getUTF8Text() ?: ""
        val hocr = api.getHOCRText(0) ?: ""
        val words = HocrParser.parseWords(hocr)
        val paragraphs = HocrParser.parseParagraphs(hocr)
        val lines = HocrParser.parseLines(hocr)
        val lineWords = HocrParser.parseLineWords(hocr)
        return OcrPageResult(fullText = text, words = words, paragraphs = paragraphs, lines = lines, lineWords = lineWords)
    }

    fun close() {
        tess?.recycle()
        tess = null
    }

    companion object {
        /**
         * Tesseract needs its language files on a normal filesystem path
         * inside a "tessdata" folder — it can't read them straight out of
         * the compressed APK assets. This copies them once (skipped on
         * later runs if already present and the same size).
         */
        private fun ensureTessDataExtracted(context: Context): File {
            val dataDir = File(context.filesDir, "tesseract")
            val tessdataDir = File(dataDir, "tessdata")
            if (!tessdataDir.exists()) tessdataDir.mkdirs()

            // A marker file (not a size/openFd check) tells us extraction
            // already ran — AssetManager.openFd() throws for assets the
            // packager compressed, which .traineddata files would be
            // without an explicit noCompress rule, so it isn't a safe way
            // to check "is this already extracted".
            val marker = File(dataDir, ".extracted")
            if (marker.exists()) return dataDir

            for (lang in listOf("ben", "eng")) {
                val outFile = File(tessdataDir, "$lang.traineddata")
                context.assets.open("tessdata/$lang.traineddata").use { input: InputStream ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
            }
            marker.writeText("ok")
            return dataDir
        }
    }
}
