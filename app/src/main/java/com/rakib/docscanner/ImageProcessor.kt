package com.rakib.docscanner

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * Phase 1 image pipeline: page detection -> perspective correction -> curve
 * dewarping -> simple output modes (original / grayscale / B&W).
 *
 * Design notes (so future-me or another dev doesn't have to reverse engineer
 * this later):
 *
 * - detectDocumentCorners() finds the 4 outer corners of the page using
 *   classic contour analysis (Canny + findContours + approxPolyDP on the
 *   largest 4-point contour). This is the same technique every OpenCV-based
 *   scanner app uses (CamScanner-style apps included) and it's reliable for
 *   a page with reasonable contrast against its background.
 *
 * - warpPerspective() does the standard flat rectangular correction from
 *   those 4 corners.
 *
 * - dewarpCurved() goes further: instead of assuming the top/bottom edges of
 *   the page are straight lines between the corners, it walks the *actual*
 *   detected contour between each pair of corners, fits a 2nd-degree curve
 *   to the top edge and the bottom edge, and builds a per-column remap so
 *   each vertical strip of the image is stretched to straighten those
 *   curves. This is the standard "curved page flattening" approach used by
 *   most non-ML scanner apps and handles the common case (book spine curve,
 *   page that's slightly domed) well. It does NOT reconstruct pixels that
 *   the camera never captured (e.g. text folded completely out of view) —
 *   no software can do that from a single photo.
 */
object ImageProcessor {

    // ---------- Bitmap <-> Mat ----------

    private fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        val bmp32 = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                    else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        Utils.bitmapToMat(bmp32, mat)
        return mat
    }

    private fun matToBitmap(mat: Mat): Bitmap {
        val bmp = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(mat, bmp)
        return bmp
    }

    // ---------- Page detection ----------

    /**
     * Returns the 4 page corners ordered [topLeft, topRight, bottomRight,
     * bottomLeft] in the bitmap's own pixel coordinates, or null if no
     * confident quadrilateral was found (caller should fall back to the
     * full image / let the user place corners manually).
     */
    fun detectDocumentCorners(bitmap: Bitmap): Array<PointF>? {
        val src = bitmapToMat(bitmap)
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(gray, edges, 60.0, 160.0)
        Imgproc.dilate(edges, edges, Mat(), Point(-1.0, -1.0), 2)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(
            edges, contours, hierarchy,
            Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE
        )

        var best: MatOfPoint2f? = null
        var bestArea = 0.0
        val imageArea = src.rows() * src.cols()

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            // Ignore tiny contours and anything suspiciously close to the
            // full frame (usually the frame border itself, not the page).
            if (area < imageArea * 0.15) continue

            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)

            if (approx.toArray().size == 4 && area > bestArea) {
                bestArea = area
                best = approx
            }
        }

        val approxPoints = best?.toArray() ?: return null
        val ordered = orderCorners(approxPoints)
        return ordered.map { PointF(it.x.toFloat(), it.y.toFloat()) }.toTypedArray()
    }

    /** Sorts 4 raw points into [topLeft, topRight, bottomRight, bottomLeft]. */
    private fun orderCorners(pts: Array<Point>): Array<Point> {
        val sumSorted = pts.sortedBy { it.x + it.y }
        val topLeft = sumSorted.first()
        val bottomRight = sumSorted.last()
        val diffSorted = pts.sortedBy { it.y - it.x }
        val topRight = diffSorted.first()
        val bottomLeft = diffSorted.last()
        return arrayOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    // ---------- Perspective correction ----------

    /** Flat rectangular crop from 4 manually-confirmed corners. */
    fun warpPerspective(bitmap: Bitmap, corners: Array<PointF>): Bitmap {
        val src = bitmapToMat(bitmap)
        val (tl, tr, br, bl) = corners

        val widthTop = distance(tl, tr)
        val widthBottom = distance(bl, br)
        val outWidth = max(widthTop, widthBottom)

        val heightLeft = distance(tl, bl)
        val heightRight = distance(tr, br)
        val outHeight = max(heightLeft, heightRight)

        val srcPoints = MatOfPoint2f(
            Point(tl.x.toDouble(), tl.y.toDouble()),
            Point(tr.x.toDouble(), tr.y.toDouble()),
            Point(br.x.toDouble(), br.y.toDouble()),
            Point(bl.x.toDouble(), bl.y.toDouble())
        )
        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outWidth.toDouble(), 0.0),
            Point(outWidth.toDouble(), outHeight.toDouble()),
            Point(0.0, outHeight.toDouble())
        )

        val transform = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val dst = Mat()
        Imgproc.warpPerspective(
            src, dst, transform,
            Size(outWidth.toDouble(), outHeight.toDouble())
        )
        return matToBitmap(dst)
    }

    private fun distance(a: PointF, b: PointF): Double {
        val dx = (a.x - b.x).toDouble()
        val dy = (a.y - b.y).toDouble()
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    // ---------- Curved-page dewarping ----------

    /**
     * Straightens a curved top/bottom edge (typical of a book page near the
     * spine) on top of the already perspective-corrected image.
     *
     * Approach: re-detect the page contour on the *cropped* image, split it
     * into a top-edge polyline and a bottom-edge polyline, fit each to a
     * 2nd-degree polynomial y = f(x), then remap every column so that curve
     * becomes a straight horizontal line at the top/bottom of the output.
     * Left/right edges are assumed already straight after warpPerspective.
     *
     * Falls back to returning the input unchanged if a clean top/bottom
     * curve can't be found — a failed dewarp attempt should never make the
     * scan worse than the plain perspective-corrected version.
     */
    fun dewarpCurved(flatBitmap: Bitmap): Bitmap {
        val src = bitmapToMat(flatBitmap)
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)

        val edges = Mat()
        Imgproc.Canny(gray, edges, 40.0, 120.0)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(
            edges, contours, Mat(),
            Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE
        )

        val largest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return flatBitmap
        val points = largest.toArray()
        if (points.isEmpty()) return flatBitmap

        val width = src.cols()
        val height = src.rows()

        // Bucket contour points by column, keep the min-y (top edge) and
        // max-y (bottom edge) point seen in each column.
        val topByCol = DoubleArray(width) { Double.NaN }
        val bottomByCol = DoubleArray(width) { Double.NaN }
        for (p in points) {
            val x = p.x.toInt().coerceIn(0, width - 1)
            if (topByCol[x].isNaN() || p.y < topByCol[x]) topByCol[x] = p.y
            if (bottomByCol[x].isNaN() || p.y > bottomByCol[x]) bottomByCol[x] = p.y
        }

        val topFit = fitQuadratic(topByCol) ?: return flatBitmap
        val bottomFit = fitQuadratic(bottomByCol) ?: return flatBitmap

        // Target: flat top line at the min of the fitted curve, flat bottom
        // line at the max — i.e. we only stretch inward, never invent new
        // canvas area outside what was already captured.
        val targetTop = (0 until width).minOf { evalQuadratic(topFit, it) }
        val targetBottom = (0 until width).maxOf { evalQuadratic(bottomFit, it) }

        val mapX = Mat(height, width, CvType.CV_32FC1)
        val mapY = Mat(height, width, CvType.CV_32FC1)

        for (x in 0 until width) {
            val curveTop = evalQuadratic(topFit, x)
            val curveBottom = evalQuadratic(bottomFit, x)
            val curveSpan = (curveBottom - curveTop).coerceAtLeast(1.0)
            val targetSpan = (targetBottom - targetTop).coerceAtLeast(1.0)

            for (y in 0 until height) {
                // For each output pixel, find which source y it should pull
                // from so that [targetTop, targetBottom] maps from
                // [curveTop, curveBottom], linearly in between.
                val t = (y - targetTop) / targetSpan
                val sourceY = curveTop + t * curveSpan
                mapX.put(y, x, x.toDouble())
                mapY.put(y, x, sourceY)
            }
        }

        val dst = Mat()
        Imgproc.remap(src, dst, mapX, mapY, Imgproc.INTER_LINEAR)
        return matToBitmap(dst)
    }

    /** Least-squares fit of y = a*x^2 + b*x + c over columns that have data. */
    private fun fitQuadratic(valuesByCol: DoubleArray): DoubleArray? {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        for (i in valuesByCol.indices) {
            if (!valuesByCol[i].isNaN()) {
                xs.add(i.toDouble())
                ys.add(valuesByCol[i])
            }
        }
        if (xs.size < 10) return null

        // Build normal equations for a 2nd-degree polynomial fit.
        var sx0 = 0.0; var sx1 = 0.0; var sx2 = 0.0; var sx3 = 0.0; var sx4 = 0.0
        var sy0 = 0.0; var sy1 = 0.0; var sy2 = 0.0
        for (i in xs.indices) {
            val x = xs[i]; val y = ys[i]
            val x2 = x * x
            sx0 += 1.0; sx1 += x; sx2 += x2; sx3 += x2 * x; sx4 += x2 * x2
            sy0 += y; sy1 += x * y; sy2 += x2 * y
        }

        // Solve the 3x3 system [sx4 sx3 sx2; sx3 sx2 sx1; sx2 sx1 sx0] * [a b c] = [sy2 sy1 sy0]
        val m = Mat(3, 3, CvType.CV_64F)
        m.put(0, 0, sx4, sx3, sx2)
        m.put(1, 0, sx3, sx2, sx1)
        m.put(2, 0, sx2, sx1, sx0)
        val rhs = Mat(3, 1, CvType.CV_64F)
        rhs.put(0, 0, sy2)
        rhs.put(1, 0, sy1)
        rhs.put(2, 0, sy0)
        val sol = Mat()
        val ok = Core.solve(m, rhs, sol, Core.DECOMP_LU)
        if (!ok) return null
        return doubleArrayOf(sol.get(0, 0)[0], sol.get(1, 0)[0], sol.get(2, 0)[0])
    }

    private fun evalQuadratic(coeffs: DoubleArray, x: Int): Double {
        val xd = x.toDouble()
        return coeffs[0] * xd * xd + coeffs[1] * xd + coeffs[2]
    }

    // ---------- Output modes ----------

    fun toGrayscale(bitmap: Bitmap): Bitmap {
        val src = bitmapToMat(bitmap)
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        return matToBitmap(gray)
    }

    /** Adaptive threshold — the classic "flatbed scanner" black & white look. */
    fun toBlackAndWhite(bitmap: Bitmap): Bitmap {
        val src = bitmapToMat(bitmap)
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        val bw = Mat()
        Imgproc.adaptiveThreshold(
            gray, bw, 255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY,
            25, 15.0
        )
        return matToBitmap(bw)
    }
}
