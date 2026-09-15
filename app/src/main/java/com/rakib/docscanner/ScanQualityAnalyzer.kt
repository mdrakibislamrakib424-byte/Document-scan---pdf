package com.rakib.docscanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.imgproc.Imgproc

/**
 * Two related but separate jobs live here:
 *
 *  - analyzeGrayFrame(): takes a raw grayscale Mat (built straight from a
 *    camera preview frame's Y-plane, no Bitmap conversion — that matters
 *    for auto-capture, which runs on every frame and can't afford a full
 *    Bitmap round-trip) and returns a blur score + brightness stats.
 *
 *  - analyzeBitmap(): the same scoring, but from a captured/cropped Bitmap,
 *    used once after crop confirmation to show the user a quality readout.
 *
 * Score meaning:
 *  - Blur: variance of the Laplacian. Low variance = few sharp edges = the
 *    image is blurry. This is the standard, well-established blur metric —
 *    not something invented for this app.
 *  - Lighting: mean brightness (0-255) and the fraction of pixels that are
 *    clipped near-black or near-white (an easy, direct signal for
 *    "shadow/hotspot too extreme to recover cleanly").
 */
object ScanQualityAnalyzer {

    data class QualityResult(
        val blurVariance: Double,
        val meanBrightness: Double,
        val clippedFraction: Double,
        val score: Int,        // 0-100
        val message: String
    )

    fun analyzeBitmap(bitmap: Bitmap): QualityResult {
        val mat = Mat()
        val bmp32 = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                    else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        Utils.bitmapToMat(bmp32, mat)
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
        return analyzeGrayMat(gray)
    }

    fun analyzeGrayMat(gray: Mat): QualityResult {
        val laplacian = Mat()
        Imgproc.Laplacian(gray, laplacian, CvType.CV_64F)
        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        org.opencv.core.Core.meanStdDev(laplacian, mean, stddev)
        val sd = stddev.toArray()[0]
        val blurVariance = sd * sd

        val meanBrightnessMat = MatOfDouble()
        val stddevBrightness = MatOfDouble()
        org.opencv.core.Core.meanStdDev(gray, meanBrightnessMat, stddevBrightness)
        val meanBrightness = meanBrightnessMat.toArray()[0]

        val totalPixels = (gray.rows() * gray.cols()).toDouble()
        val nearBlack = Mat()
        val nearWhite = Mat()
        Imgproc.threshold(gray, nearBlack, 15.0, 255.0, Imgproc.THRESH_BINARY_INV)
        Imgproc.threshold(gray, nearWhite, 240.0, 255.0, Imgproc.THRESH_BINARY)
        val clippedCount = org.opencv.core.Core.countNonZero(nearBlack) +
                org.opencv.core.Core.countNonZero(nearWhite)
        val clippedFraction = clippedCount / totalPixels

        // Blur: variance below ~60 on a normal document photo reads as
        // visibly soft; above ~150 reads as crisp. Scaled to 0-100.
        val blurScore = (blurVariance / 150.0 * 100.0).coerceIn(0.0, 100.0)

        // Lighting: penalize being far from a comfortable mid-bright page
        // (documents should sit bright, ~180-235) and penalize clipping.
        val brightnessScore = when {
            meanBrightness in 170.0..245.0 -> 100.0
            meanBrightness < 170.0 -> (meanBrightness / 170.0 * 100.0).coerceIn(0.0, 100.0)
            else -> ((255.0 - meanBrightness) / 10.0 * 100.0).coerceIn(0.0, 100.0)
        }
        val clippingPenalty = (clippedFraction * 200.0).coerceIn(0.0, 40.0)

        val overall = (blurScore * 0.6 + brightnessScore * 0.4 - clippingPenalty)
            .coerceIn(0.0, 100.0).toInt()

        val message = buildMessage(overall, blurScore, brightnessScore, meanBrightness)

        return QualityResult(blurVariance, meanBrightness, clippedFraction, overall, message)
    }

    private fun buildMessage(overall: Int, blurScore: Double, brightnessScore: Double, meanBrightness: Double): String {
        if (overall >= 80) return "Excellent scan — text clarity is high."
        val issues = mutableListOf<String>()
        if (blurScore < 50) issues.add("image looks a bit blurry — hold the phone steadier or move closer")
        if (meanBrightness < 130) issues.add("lighting is low — try a brighter, more even light source")
        if (meanBrightness > 245) issues.add("image looks overexposed/washed out")
        return if (issues.isEmpty()) "Good scan." else "Could be better: " + issues.joinToString("; ")
    }
}
