package com.rakib.docscanner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PP-OCRv5 mobile text recognition (CRNN + CTC), ONNX. Reads the text
 * inside one already-cropped text-line image (from
 * [PaddleTextDetector]'s box).
 *
 * Verified against the model's own published inference.yml
 * (en_PP-OCRv5_mobile_rec, same family/generation as the multilingual
 * model this app actually bundles — pipeline shape is shared across the
 * PP-OCRv5_rec line): DecodeImage BGR, RecResizeImg to a fixed height,
 * variable width; PostProcess is `CTCLabelDecode` — plain CTC greedy
 * decoding, not an autoregressive decoder like SLANet_plus, so there's no
 * token-stream/grid-assembly complexity here.
 *
 * NOT independently verified:
 *  - The exact per-pixel normalization for recognition specifically.
 *    PaddleOCR's rec preprocessing conventionally bakes scale-to-[-1,1]
 *    (mean=0.5, std=0.5 on all three channels after /255) directly into
 *    its resize step rather than a separate NormalizeImage transform —
 *    consistent with the fetched inference.yml listing DecodeImage then
 *    RecResizeImg with no separate NormalizeImage in between. Used here,
 *    but not checked against this exact ONNX export's actual expectation.
 *  - The fixed input height. A real bug report (PaddleOCR GitHub
 *    discussion #15712) found a PP-OCRv5 rec fine-tune's documented
 *    default (48) produced garbage at inference while 32 worked
 *    correctly — i.e. even PaddleOCR's own shipped configs have shown
 *    real train/inference mismatches in the wild for this exact model
 *    family. Rather than hardcode 48 (or 32) and hope, [init] reads the
 *    ONNX model's own declared input shape and uses whatever height it
 *    actually specifies, falling back to 48 only if the model declares a
 *    dynamic (unspecified) height.
 *  - Index 0 as the CTC blank token is PaddleOCR's well-documented,
 *    longstanding convention (not specific to v5) — high confidence, but
 *    still listed here since decode is meaningless if this is wrong.
 */
class PaddleTextRecognizer(private val context: Context) {

    companion object {
        private const val MODEL_ASSET_PATH = "models/ppocr_en_rec.onnx"
        private const val DICT_ASSET_PATH = "models/ppocr_en_dict.txt"
        private const val DEFAULT_HEIGHT = 48
        private const val MAX_WIDTH = 320
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var vocabulary: List<String> = emptyList() // index 0 = CTC blank; real characters start at index 1
    private var inputHeight = DEFAULT_HEIGHT

    fun init(): Boolean {
        return try {
            vocabulary = listOf("") + context.assets.open(DICT_ASSET_PATH).bufferedReader(Charsets.UTF_8)
                .readLines()
                .map { it.trimEnd('\n', '\r') }
                .filter { it.isNotEmpty() }

            val modelBytes = context.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
            val ortEnv = OrtEnvironment.getEnvironment()
            val ortSession = ortEnv.createSession(modelBytes, OrtSession.SessionOptions())
            env = ortEnv
            session = ortSession

            // Prefer the model's own declared height over a hardcoded
            // guess — see class kdoc on why a hardcoded default has
            // actually bitten real PaddleOCR users on this exact model
            // family.
            val declaredShape = ortSession.inputInfo.values.firstOrNull()?.info
                ?.let { it as? ai.onnxruntime.TensorInfo }?.shape
            if (declaredShape != null && declaredShape.size == 4 && declaredShape[2] > 0) {
                inputHeight = declaredShape[2].toInt()
            }

            true
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
    }

    /** Recognizes the text in one cropped line image, or "" on failure — never throws, since one bad crop shouldn't fail a whole page. */
    fun recognize(lineCrop: Bitmap): String {
        val activeSession = session ?: return ""
        if (vocabulary.size <= 1) return ""

        return try {
            val prep = preprocess(lineCrop)
            val inputTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(prep.chwData),
                longArrayOf(1, 3, inputHeight.toLong(), prep.width.toLong())
            )
            val logits = runInference(activeSession, inputTensor) ?: return ""
            ctcGreedyDecode(logits)
        } catch (e: Exception) {
            ""
        }
    }

    // ---------- Preprocessing ----------

    private class PreprocessResult(val chwData: FloatArray, val width: Int)

    /**
     * Resize to the model's fixed height, preserving aspect ratio, capped
     * at [MAX_WIDTH] (downscaled further if the line is unusually wide
     * relative to its height — long lines still get read, just at lower
     * effective resolution per character, the same tradeoff PaddleOCR's
     * own fixed-width batching makes).
     */
    private fun preprocess(bitmap: Bitmap): PreprocessResult {
        val aspect = bitmap.width.toDouble() / bitmap.height.toDouble()
        val targetWidth = min(MAX_WIDTH, (inputHeight * aspect).roundToInt()).coerceAtLeast(1)

        val mat = Mat()
        val bmp32 = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                    else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        Utils.bitmapToMat(bmp32, mat)
        val bgr = Mat()
        Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_RGBA2BGR)

        val resized = Mat()
        Imgproc.resize(bgr, resized, Size(targetWidth.toDouble(), inputHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

        val floatMat = Mat()
        // Rec-specific normalization: scale to [-1,1] (mean=std=0.5 on
        // every channel) rather than the ImageNet mean/std used
        // elsewhere in this app — see class kdoc.
        resized.convertTo(floatMat, CvType.CV_32FC3, 1.0 / 255.0)
        Core.subtract(floatMat, Scalar(0.5, 0.5, 0.5), floatMat)
        Core.divide(floatMat, Scalar(0.5, 0.5, 0.5), floatMat)

        val chwData = FloatArray(3 * targetWidth * inputHeight)
        val plane = targetWidth * inputHeight
        val rowBuf = FloatArray(targetWidth * 3)
        for (y in 0 until inputHeight) {
            floatMat.get(y, 0, rowBuf)
            for (x in 0 until targetWidth) {
                val base = x * 3
                chwData[0 * plane + y * targetWidth + x] = rowBuf[base]
                chwData[1 * plane + y * targetWidth + x] = rowBuf[base + 1]
                chwData[2 * plane + y * targetWidth + x] = rowBuf[base + 2]
            }
        }
        return PreprocessResult(chwData, targetWidth)
    }

    // ---------- Inference ----------

    /** Returns per-timestep logits [T][V], or null on failure. */
    private fun runInference(session: OrtSession, imageTensor: OnnxTensor): Array<FloatArray>? {
        return try {
            val inputName = session.inputNames.iterator().next()
            val result = session.run(mapOf(inputName to imageTensor))
            try {
                val entry = result.iterator().next()
                val tensor = entry.value as? OnnxTensor ?: return null
                val shape = tensor.info.shape
                if (shape.size != 3) return null
                @Suppress("UNCHECKED_CAST")
                (tensor.value as Array<Array<FloatArray>>)[0]
            } finally {
                result.close()
            }
        } catch (e: Exception) {
            null
        } finally {
            imageTensor.close()
        }
    }

    // ---------- CTC decode ----------

    /** Standard CTC greedy decode: argmax per step, collapse consecutive repeats, drop blanks (index 0). */
    private fun ctcGreedyDecode(logits: Array<FloatArray>): String {
        val sb = StringBuilder()
        var lastIdx = -1
        for (step in logits) {
            var bestIdx = 0
            var bestVal = Float.NEGATIVE_INFINITY
            for (i in step.indices) if (step[i] > bestVal) { bestVal = step[i]; bestIdx = i }

            if (bestIdx != 0 && bestIdx != lastIdx && bestIdx < vocabulary.size) {
                sb.append(vocabulary[bestIdx])
            }
            lastIdx = bestIdx
        }
        return sb.toString()
    }
}
