package com.rakib.docscanner

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo

/**
 * Phase 2: image-quality enhancement, run on the already perspective
 * corrected + dewarped Phase 1 output.
 *
 * Every function here is a real, working classical (non-ML) algorithm —
 * exactly what's documented below, nothing hand-waved. Where a "true AI"
 * version of a technique exists and would do meaningfully better
 * (deblurring, super-resolution), that's called out explicitly so it's not
 * confused with something it isn't.
 */
object Phase2Processor {

    private fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        val bmp32 = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                    else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        Utils.bitmapToMat(bmp32, mat)
        // Drop alpha — every filter below works in 3-channel RGB space.
        val rgb = Mat()
        Imgproc.cvtColor(mat, rgb, Imgproc.COLOR_RGBA2RGB)
        return rgb
    }

    private fun matToBitmap(mat: Mat): Bitmap {
        val rgba = Mat()
        Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_RGB2RGBA)
        val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        return bmp
    }

    // ---------- 1. Shadow removal / uneven lighting correction ----------

    /**
     * Standard "divide by estimated background" illumination normalization:
     *  1. Blur the image heavily (large kernel) to get an estimate of just
     *     the *lighting* — shadows, gradients, hotspots — with the text
     *     washed out.
     *  2. Divide the original by that lighting estimate and rescale to
     *     [0,255]. Wherever the page was in shadow, the divide brightens it
     *     back up; wherever it was over-lit, it's pulled back down. Text
     *     strokes, being locally dark relative to their neighborhood,
     *     survive the divide as dark strokes on a now-even white page.
     *
     * This is the same core idea used by most real "flatten lighting"
     * scanner features (it's a classical technique, predates any AI scanner
     * app). Kernel size is a fraction of image size so it scales with
     * resolution instead of a fixed pixel count.
     */
    fun removeShadowAndNormalizeLighting(bitmap: Bitmap): Bitmap {
        val src = bitmapToMat(bitmap)
        val srcF = Mat()
        src.convertTo(srcF, CvType.CV_32FC3)

        // Kernel ~1/8th of the shorter side, forced odd, floor at 31px.
        val shortSide = minOf(src.rows(), src.cols())
        var k = (shortSide / 8)
        if (k % 2 == 0) k += 1
        if (k < 31) k = 31

        val background = Mat()
        Imgproc.GaussianBlur(srcF, background, Size(k.toDouble(), k.toDouble()), 0.0)

        // Avoid divide-by-zero on pure black regions.
        Core.add(background, org.opencv.core.Scalar(1.0, 1.0, 1.0), background)

        val normalized = Mat()
        Core.divide(srcF, background, normalized, 255.0)

        val clipped = Mat()
        Core.min(normalized, org.opencv.core.Scalar(255.0, 255.0, 255.0), clipped)
        val result8u = Mat()
        clipped.convertTo(result8u, CvType.CV_8UC3)

        return matToBitmap(result8u)
    }

    // ---------- 2. Noise removal ----------

    /**
     * Non-local means denoising (OpenCV's `Photo` module) — much better at
     * preserving text edges than a simple blur, which is why it's the
     * standard choice for document/photo denoising rather than
     * GaussianBlur or median blur.
     */
    fun denoise(bitmap: Bitmap): Bitmap {
        val src = bitmapToMat(bitmap)
        val dst = Mat()
        Photo.fastNlMeansDenoisingColored(src, dst, 6f, 6f, 7, 21)
        return matToBitmap(dst)
    }

    // ---------- 3. Smart contrast ----------

    /**
     * CLAHE (Contrast Limited Adaptive Histogram Equalization) applied to
     * the lightness channel only (in Lab color space), so contrast is
     * boosted locally — different parts of the page can each get their own
     * contrast stretch — without shifting color balance the way running
     * CLAHE on R/G/B separately would.
     */
    fun smartContrast(bitmap: Bitmap, clipLimit: Double = 2.5): Bitmap {
        val src = bitmapToMat(bitmap)
        val lab = Mat()
        Imgproc.cvtColor(src, lab, Imgproc.COLOR_RGB2Lab)

        val channels = ArrayList<Mat>()
        Core.split(lab, channels)

        val clahe = Imgproc.createCLAHE(clipLimit, Size(8.0, 8.0))
        val lEnhanced = Mat()
        clahe.apply(channels[0], lEnhanced)
        channels[0] = lEnhanced

        val merged = Mat()
        Core.merge(channels, merged)
        val result = Mat()
        Imgproc.cvtColor(merged, result, Imgproc.COLOR_Lab2RGB)
        return matToBitmap(result)
    }

    // ---------- 4. Text sharpening / "deblur" ----------

    /**
     * Unsharp masking: blur the image, subtract that blur from the
     * original (weighted), which amplifies edges — i.e. text strokes.
     *
     * Honest note: this is sharpening, not true deblurring. Real blind
     * deconvolution (recovering detail lost to actual motion/focus blur)
     * needs to estimate the blur kernel (the point-spread function) first,
     * which is unstable on general photos and usually needs a trained
     * model to do reliably. Unsharp masking is what essentially every
     * mobile scanner app actually ships for this — it doesn't invent lost
     * detail, but it makes existing edges read as crisper, which is what
     * "make blurry text more readable" mostly needs in practice.
     */
    fun sharpenText(bitmap: Bitmap, amount: Double = 1.5): Bitmap {
        val src = bitmapToMat(bitmap)
        val blurred = Mat()
        Imgproc.GaussianBlur(src, blurred, Size(0.0, 0.0), 3.0)
        val sharpened = Mat()
        Core.addWeighted(src, 1.0 + amount, blurred, -amount, 0.0, sharpened)
        return matToBitmap(sharpened)
    }

    // ---------- 5. Upscale ("super-resolution") ----------

    /**
     * Honest note upfront: this is high-quality classical upscaling
     * (Lanczos interpolation) plus a mild sharpen pass — NOT learned/AI
     * super-resolution. True AI super-resolution (ESRGAN-style) invents
     * plausible fine detail using a trained model and genuinely looks
     * sharper on close inspection; that needs a bundled TFLite model file,
     * which is a separate, heavier addition on top of this. This function
     * gives you a clean, well-interpolated larger image now — a real and
     * useful upgrade over "just stretch the bitmap" — without overselling
     * it as AI-generated detail it doesn't contain.
     */
    fun upscale(bitmap: Bitmap, factor: Double = 2.0): Bitmap {
        val src = bitmapToMat(bitmap)
        val resized = Mat()
        val newSize = Size(src.cols() * factor, src.rows() * factor)
        Imgproc.resize(src, resized, newSize, 0.0, 0.0, Imgproc.INTER_LANCZOS4)

        val blurred = Mat()
        Imgproc.GaussianBlur(resized, blurred, Size(0.0, 0.0), 2.0)
        val sharpened = Mat()
        Core.addWeighted(resized, 1.3, blurred, -0.3, 0.0, sharpened)

        return matToBitmap(sharpened)
    }

    // ---------- Combined pipeline ----------

    /**
     * The "AI Enhance" mode shown in the app: runs the full chain in the
     * order that actually works best in practice — fix lighting first
     * (so denoise/contrast aren't fighting shadows), then denoise (before
     * sharpening, so we don't sharpen noise into the image), then boost
     * local contrast, then sharpen text last.
     *
     * @param intensity 0.0 (barely-there enhancement) .. 2.0 (aggressive).
     *   1.0 is the balanced default. Drives the Low-High slider in
     *   ReviewActivity — scales CLAHE's clip limit and the unsharp amount
     *   together so "more enhancement" reads as one consistent dial rather
     *   than several independent ones the user has to reason about.
     */
    fun enhanceFull(bitmap: Bitmap, intensity: Double = 1.0): Bitmap {
        val litEvenly = removeShadowAndNormalizeLighting(bitmap)
        val denoised = denoise(litEvenly)
        val contrasted = smartContrast(denoised, clipLimit = 2.5 * intensity)
        return sharpenText(contrasted, amount = 1.2 * intensity)
    }
}
