package com.rakib.docscanner

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Runs PP-DocLayout-S (via ONNX Runtime) to find out *where* the title,
 * body text, tables, and images sit on a page and what each region is —
 * before any OCR happens. This is the piece the Bengali-OCR correction in
 * Phase 5 explicitly left out: PaddleOCR's own PP-Structure layout model
 * is bundled with — and only really documented for — PaddleOCR's own
 * pipeline, but a *layout* detector (unlike a *text* recognizer) doesn't
 * need to know what script the page is written in. It only looks at
 * shapes: a block of dense small marks is "text", a grid of ruled lines is
 * "table", a title sits alone above a block. That's exactly why this one
 * piece of PP-Structure carries over even though PaddleOCR's own text
 * recognizer doesn't (see OcrEngine's kdoc).
 *
 * Model contract this class assumes (from the ONNX export's own README —
 * not something guessed): two inputs, `image` (float32 [1,3,480,480] — the
 * source resized to exactly 480x480, *not* keeping aspect ratio, scaled to
 * [0,1] and then ImageNet-normalized) and `scale_factor` (float32 [1,2] =
 * [480/origH, 480/origW], so the model hands boxes back already converted
 * into the original image's own pixel space). Outputs are a padded
 * `[M, 6]` detections tensor (`class_id, score, x1, y1, x2, y2` per row)
 * and a scalar count of how many of those M rows are real (the rest is
 * padding to a fixed size) — NMS is already baked into the graph at its
 * own internal 0.3 threshold, so this class's own [scoreThreshold] is an
 * *additional*, stricter filter on top of that, not a replacement for it.
 *
 * One real limitation, stated plainly: this was written and reasoned
 * about against the model's documented I/O contract, but never actually
 * run against a real ONNX Runtime session on a device or emulator — this
 * environment can't execute Android/ONNX code to verify it. If output
 * tensor names in practice don't match what's assumed here, [analyze]
 * looks them up by *shape* and *dtype* instead of by name specifically to
 * absorb that risk, but a genuine mismatch in the model file would still
 * surface as [analyze] returning no regions (and DocumentStructureEngine
 * falling back to the older flat-OCR retypeset) rather than a crash.
 */
class LayoutEngine(private val context: Context) {

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    /** Loads the bundled model and starts an inference session. Returns false (never throws) if anything about that fails. */
    fun init(): Boolean {
        return try {
            val modelBytes = context.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            val newSession = environment.createSession(modelBytes, options)
            env = environment
            session = newSession
            true
        } catch (e: Exception) {
            // Missing/corrupt model asset, or an ONNX Runtime failure on
            // this device — either way, the caller falls back to the
            // plain-text retypeset path rather than this being fatal.
            env = null
            session = null
            false
        }
    }

    /**
     * Detects layout regions on one page bitmap, already sorted into
     * reading order (see [ReadingOrder]). [scoreThreshold] is applied on
     * top of the model's own baked-in NMS threshold (0.3) — 0.4 by default
     * so a shaky low-confidence box doesn't get treated as real content.
     */
    fun analyze(bitmap: Bitmap, scoreThreshold: Float = 0.4f): List<LayoutRegion> {
        val activeSession = session ?: throw IllegalStateException("LayoutEngine.init() was not called or failed")
        val activeEnv = env ?: throw IllegalStateException("LayoutEngine.init() was not called or failed")

        val origW = bitmap.width
        val origH = bitmap.height

        val imageTensor = buildImageTensor(activeEnv, bitmap)
        val scaleFactorTensor = buildScaleFactorTensor(activeEnv, origW, origH)

        val regions: List<LayoutRegion>
        try {
            val result = activeSession.run(mapOf(INPUT_IMAGE to imageTensor, INPUT_SCALE_FACTOR to scaleFactorTensor))
            try {
                var detections: OnnxTensor? = null
                var numDets: OnnxTensor? = null
                for (entry in result) {
                    val value = entry.value
                    if (value !is OnnxTensor) continue
                    val shape = value.info.shape
                    val isIntType = value.info.type == OnnxJavaType.INT32 || value.info.type == OnnxJavaType.INT64
                    // A "scalar count" isn't always exported as a true
                    // 0-d tensor (shape []) — some converters keep it as
                    // a 1-element 1-d tensor (shape [1]) instead. Also
                    // accepted here, since a genuine audit couldn't run
                    // this against the real model file to see which one
                    // it actually is.
                    val looksLikeCount = isIntType && (shape.isEmpty() || (shape.size == 1 && shape[0] == 1L))
                    // Similarly: the documented contract is [M, 6] with
                    // no batch dimension, but [1, M, 6] is a common
                    // alternative some export tools produce — matched
                    // here too so a batch-wrapped export doesn't just
                    // silently fail to be recognized as the detections
                    // tensor.
                    val looksLikeDetections = value.info.type == OnnxJavaType.FLOAT &&
                        ((shape.size == 2 && shape[1] == 6L) || (shape.size == 3 && shape[0] == 1L && shape[2] == 6L))
                    when {
                        looksLikeDetections -> detections = value
                        looksLikeCount -> numDets = value
                    }
                }
                regions = parseDetections(detections, numDets, scoreThreshold)
            } finally {
                result.close()
            }
        } finally {
            imageTensor.close()
            scaleFactorTensor.close()
        }

        return ReadingOrder.sort(regions)
    }

    fun close() {
        session?.close()
        env = null
        session = null
    }

    private fun parseDetections(detections: OnnxTensor?, numDets: OnnxTensor?, scoreThreshold: Float): List<LayoutRegion> {
        if (detections == null) return emptyList()

        // Unwrap either the documented [M, 6] shape or a [1, M, 6]
        // batch-wrapped variant (see the shape-matching above) into a
        // plain Array<FloatArray> either way.
        @Suppress("UNCHECKED_CAST")
        val rows = when (val raw = detections.value) {
            is Array<*> -> when {
                raw.isEmpty() -> emptyArray()
                raw[0] is FloatArray -> raw as Array<FloatArray>
                raw[0] is Array<*> -> (raw[0] as Array<FloatArray>) // [1, M, 6] -> take the single batch entry
                else -> return emptyList()
            }
            else -> return emptyList()
        }
        if (rows.isEmpty()) return emptyList()

        // A count tensor's own .value can come back as a plain Int/Long
        // (true scalar) or as a single-element array (the [1]-shaped
        // variant matched above) — both are handled the same way.
        val validCount = when (val raw = numDets?.value) {
            is Int -> raw
            is Long -> raw.toInt()
            is IntArray -> raw.firstOrNull() ?: rows.size
            is LongArray -> raw.firstOrNull()?.toInt() ?: rows.size
            else -> rows.size // no count tensor found — fall back to trusting every padded row (filtered by score below anyway)
        }.coerceIn(0, rows.size)

        val regions = mutableListOf<LayoutRegion>()
        for (i in 0 until validCount) {
            val row = rows[i]
            if (row.size < 6) continue
            val classId = row[0].toInt()
            val score = row[1]
            if (score < scoreThreshold) continue
            val layoutClass = LayoutClass.fromId(classId) ?: continue

            val left = row[2].toInt().coerceAtLeast(0)
            val top = row[3].toInt().coerceAtLeast(0)
            val right = row[4].toInt()
            val bottom = row[5].toInt()
            if (right <= left || bottom <= top) continue

            regions.add(LayoutRegion(layoutClass, score, left, top, right, bottom))
        }
        return regions
    }

    /** Bilinear-resizes to 480x480 (no aspect-ratio preservation — the model was trained square), scales to [0,1], then ImageNet-normalizes, laid out CHW as the model expects. */
    private fun buildImageTensor(env: OrtEnvironment, bitmap: Bitmap): OnnxTensor {
        val resized = Bitmap.createScaledBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE, true)
        val pixels = IntArray(MODEL_INPUT_SIZE * MODEL_INPUT_SIZE)
        resized.getPixels(pixels, 0, MODEL_INPUT_SIZE, 0, 0, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)
        if (resized !== bitmap) resized.recycle()

        val byteBuffer = ByteBuffer
            .allocateDirect(3 * MODEL_INPUT_SIZE * MODEL_INPUT_SIZE * 4)
            .order(ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()

        // CHW: write all of channel R, then all of channel G, then all of channel B.
        for (channel in 0 until 3) {
            val mean = IMAGENET_MEAN[channel]
            val std = IMAGENET_STD[channel]
            for (pixel in pixels) {
                val component = when (channel) {
                    0 -> (pixel shr 16) and 0xFF // R
                    1 -> (pixel shr 8) and 0xFF  // G
                    else -> pixel and 0xFF        // B
                }
                floatBuffer.put(((component / 255f) - mean) / std)
            }
        }
        floatBuffer.rewind()
        return OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, 3, MODEL_INPUT_SIZE.toLong(), MODEL_INPUT_SIZE.toLong()))
    }

    private fun buildScaleFactorTensor(env: OrtEnvironment, origW: Int, origH: Int): OnnxTensor {
        val byteBuffer = ByteBuffer.allocateDirect(2 * 4).order(ByteOrder.nativeOrder())
        val floatBuffer = byteBuffer.asFloatBuffer()
        floatBuffer.put(MODEL_INPUT_SIZE / origH.toFloat())
        floatBuffer.put(MODEL_INPUT_SIZE / origW.toFloat())
        floatBuffer.rewind()
        return OnnxTensor.createTensor(env, floatBuffer, longArrayOf(1, 2))
    }

    companion object {
        private const val MODEL_ASSET_PATH = "models/pp_doclayout_s.onnx"
        private const val MODEL_INPUT_SIZE = 480
        private const val INPUT_IMAGE = "image"
        private const val INPUT_SCALE_FACTOR = "scale_factor"
        private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }
}
