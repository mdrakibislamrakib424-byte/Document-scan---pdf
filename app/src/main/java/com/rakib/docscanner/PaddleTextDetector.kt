package com.rakib.docscanner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PP-OCRv5 mobile text detection (DB — Differentiable Binarization),
 * ONNX. Finds where text LINES are on a page; [PaddleTextRecognizer]
 * reads what each one says.
 *
 * Verified (PaddleOCR's own published det configs, consistent across
 * PP-OCRv5_mobile/server and PP-OCRv4 — the preprocessing hasn't changed
 * across these generations): DecodeImage img_mode BGR, NormalizeImage
 * scale 1/255 + ImageNet mean/std applied in that same BGR order (same
 * formula already used for PP-DocLayout-S and SLANet_plus in this app),
 * ToCHWImage. Resize: "DetResizeForTest" — PaddleOCR's documented default
 * is limiting the longer side to a configurable bound (960 is the common
 * default multiple community ports use) while keeping aspect ratio, with
 * both resulting dimensions rounded to a multiple of 32 (a real,
 * well-known requirement of DB's downsampling stride — an input not
 * divisible by 32 either fails or silently misaligns the network's
 * feature maps).
 *
 * NOT independently verified: the post-processing constants (binarization
 * threshold 0.3, box-score threshold 0.6, unclip ratio 1.5) come from one
 * community ONNX-pipeline's config, presented as matching PaddleOCR's own
 * defaults — plausible (they're the well-known, oft-cited DB paper /
 * PaddleOCR defaults) but not checked against this exact exported model's
 * own bundled config. A wrong threshold here would mean detecting too
 * many/few text regions, not a crash or garbled text.
 */
class PaddleTextDetector(private val context: Context) {

    companion object {
        private const val MODEL_ASSET_PATH = "models/ppocr_en_det.ort"
        private const val LIMIT_SIDE_LEN = 960
        private const val BINARIZE_THRESH = 0.3
        private const val BOX_SCORE_THRESH = 0.6
        private const val UNCLIP_RATIO = 1.5
        private const val MIN_BOX_SIDE_PX = 4
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    fun init(): Boolean {
        return try {
            val modelBytes = context.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
            val ortEnv = OrtEnvironment.getEnvironment()
            // .ort is ONNX Runtime's own serialized format — createSession
            // accepts it through the same byte[] API as a plain .onnx file.
            val ortSession = ortEnv.createSession(modelBytes, OrtSession.SessionOptions())
            env = ortEnv
            session = ortSession
            true
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
    }

    /** Returns detected text-line boxes in the ORIGINAL bitmap's pixel space, or empty on failure. */
    fun detect(bitmap: Bitmap): List<Rect> {
        val activeSession = session ?: return emptyList()
        return try {
            val prep = preprocess(bitmap)
            val inputTensor = OnnxTensor.createTensor(
                env, FloatBuffer.wrap(prep.chwData),
                longArrayOf(1, 3, prep.resizedH.toLong(), prep.resizedW.toLong())
            )
            val probMap = runInference(activeSession, inputTensor, prep.resizedH, prep.resizedW) ?: return emptyList()
            boxesFromProbMap(probMap, prep)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- Preprocessing ----------

    private class PreprocessResult(
        val chwData: FloatArray,
        val resizedW: Int,
        val resizedH: Int,
        val origW: Int,
        val origH: Int,
        val scaleX: Double,
        val scaleY: Double
    )

    private fun preprocess(bitmap: Bitmap): PreprocessResult {
        val origW = bitmap.width
        val origH = bitmap.height

        val longSide = max(origW, origH)
        val ratio = if (longSide > LIMIT_SIDE_LEN) LIMIT_SIDE_LEN.toDouble() / longSide else 1.0
        var resizedW = (origW * ratio).roundToInt()
        var resizedH = (origH * ratio).roundToInt()
        // DB's downsampling stride requires both dims to be a multiple of
        // 32 — round to the nearest multiple, minimum 32.
        resizedW = max(32, (resizedW / 32.0).roundToInt() * 32)
        resizedH = max(32, (resizedH / 32.0).roundToInt() * 32)

        val mat = Mat()
        val bmp32 = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                    else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        Utils.bitmapToMat(bmp32, mat)
        val bgr = Mat()
        Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_RGBA2BGR)

        val resized = Mat()
        Imgproc.resize(bgr, resized, Size(resizedW.toDouble(), resizedH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

        val floatMat = Mat()
        resized.convertTo(floatMat, CvType.CV_32FC3, 1.0 / 255.0)
        Core.subtract(floatMat, Scalar(0.485, 0.456, 0.406), floatMat)
        Core.divide(floatMat, Scalar(0.229, 0.224, 0.225), floatMat)

        val chwData = FloatArray(3 * resizedW * resizedH)
        val plane = resizedW * resizedH
        val rowBuf = FloatArray(resizedW * 3)
        for (y in 0 until resizedH) {
            floatMat.get(y, 0, rowBuf)
            for (x in 0 until resizedW) {
                val base = x * 3
                chwData[0 * plane + y * resizedW + x] = rowBuf[base]
                chwData[1 * plane + y * resizedW + x] = rowBuf[base + 1]
                chwData[2 * plane + y * resizedW + x] = rowBuf[base + 2]
            }
        }

        return PreprocessResult(
            chwData, resizedW, resizedH, origW, origH,
            scaleX = resizedW.toDouble() / origW, scaleY = resizedH.toDouble() / origH
        )
    }

    // ---------- Inference ----------

    /** Returns the probability map as a flat row-major FloatArray sized resizedH*resizedW, or null on failure. */
    private fun runInference(session: OrtSession, imageTensor: OnnxTensor, resizedH: Int, resizedW: Int): FloatArray? {
        return try {
            val inputName = session.inputNames.iterator().next()
            val result = session.run(mapOf(inputName to imageTensor))
            try {
                val entry = result.iterator().next()
                val tensor = entry.value as? OnnxTensor ?: return null
                val shape = tensor.info.shape
                // DB's output is a single-channel probability map, exported
                // as either [1,1,H,W] or [1,H,W] depending on the export
                // tool — both handled since which one this file uses
                // wasn't confirmed.
                val flat = FloatArray(resizedH * resizedW)
                when (shape.size) {
                    4 -> {
                        @Suppress("UNCHECKED_CAST")
                        val arr = tensor.value as Array<Array<Array<FloatArray>>>
                        var idx = 0
                        for (y in 0 until resizedH) for (x in 0 until resizedW) flat[idx++] = arr[0][0][y][x]
                    }
                    3 -> {
                        @Suppress("UNCHECKED_CAST")
                        val arr = tensor.value as Array<Array<FloatArray>>
                        var idx = 0
                        for (y in 0 until resizedH) for (x in 0 until resizedW) flat[idx++] = arr[0][y][x]
                    }
                    else -> return null
                }
                flat
            } finally {
                result.close()
            }
        } catch (e: Exception) {
            null
        } finally {
            imageTensor.close()
        }
    }

    // ---------- Post-processing (DB box extraction) ----------

    private fun boxesFromProbMap(probMap: FloatArray, prep: PreprocessResult): List<Rect> {
        val probMat = Mat(prep.resizedH, prep.resizedW, CvType.CV_32FC1)
        probMat.put(0, 0, probMap)

        val binary = Mat()
        Imgproc.threshold(probMat, binary, BINARIZE_THRESH, 1.0, Imgproc.THRESH_BINARY)
        val binary8u = Mat()
        binary.convertTo(binary8u, CvType.CV_8UC1, 255.0)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(binary8u, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        val boxes = mutableListOf<Rect>()
        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            if (rect.width < MIN_BOX_SIDE_PX || rect.height < MIN_BOX_SIDE_PX) continue

            // Box score: mean probability inside this box, on the
            // probability map (not the thresholded binary) — a weak
            // detection that only barely crossed the binarize threshold
            // gets filtered out here even though it survived thresholding.
            val roi = Mat(probMat, org.opencv.core.Rect(rect.x, rect.y, rect.width, rect.height))
            val meanScore = Core.mean(roi).`val`[0]
            if (meanScore < BOX_SCORE_THRESH) continue

            // Unclip: expand the tight box outward by the standard
            // DB formula (offset = area * unclip_ratio / perimeter),
            // applied per side rather than a true polygon offset since
            // our boxes are already axis-aligned rectangles (the
            // rectangle case of the general polygon-unclip operation).
            val area = (rect.width * rect.height).toDouble()
            val perimeter = 2.0 * (rect.width + rect.height)
            val offset = if (perimeter > 0) area * UNCLIP_RATIO / perimeter else 0.0

            val expandedLeft = (rect.x - offset).coerceAtLeast(0.0)
            val expandedTop = (rect.y - offset).coerceAtLeast(0.0)
            val expandedRight = (rect.x + rect.width + offset).coerceAtMost(prep.resizedW.toDouble())
            val expandedBottom = (rect.y + rect.height + offset).coerceAtMost(prep.resizedH.toDouble())

            // Map back to the ORIGINAL bitmap's pixel space.
            val origLeft = (expandedLeft / prep.scaleX).roundToInt()
            val origTop = (expandedTop / prep.scaleY).roundToInt()
            val origRight = (expandedRight / prep.scaleX).roundToInt().coerceAtMost(prep.origW)
            val origBottom = (expandedBottom / prep.scaleY).roundToInt().coerceAtMost(prep.origH)

            if (origRight > origLeft && origBottom > origTop) {
                boxes.add(Rect(origLeft, origTop, origRight, origBottom))
            }
        }
        return boxes
    }
}
