package com.rakib.docscanner

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * Stateful frame-by-frame checker for auto-capture. Feed it every preview
 * frame via [onFrame]; it returns true exactly once, on the frame where it
 * decides to fire the shutter (caller is responsible for actually
 * capturing and should stop feeding frames until the next scan attempt).
 *
 * Conditions checked every frame:
 *  1. A 4-point quadrilateral is found covering a large enough fraction of
 *     the frame (the page is actually in view, not half cut off).
 *  2. The frame isn't blurry (reuses ScanQualityAnalyzer's Laplacian-variance metric).
 *  3. The detected quad's corners haven't moved much versus the last frame
 *     (the user's hand has stopped moving).
 *
 * Only once all three hold for [requiredStableFrames] frames in a row does
 * it fire — this avoids capturing mid-motion just because one lucky frame
 * happened to look fine.
 */
class AutoCaptureController(
    private val requiredStableFrames: Int = 8,
    private val minAreaFraction: Double = 0.35,
    private val minBlurScore: Double = 45.0,
    private val maxCornerJitterPx: Double = 14.0
) {
    private var stableCount = 0
    private var lastCorners: Array<Point>? = null

    /** Returns true on the frame that should trigger capture. */
    fun onFrame(gray: Mat): Boolean {
        val corners = detectQuad(gray)
        if (corners == null) {
            reset()
            return false
        }

        val area = polygonArea(corners)
        val frameArea = gray.rows() * gray.cols()
        if (area < frameArea * minAreaFraction) {
            reset()
            return false
        }

        val quality = ScanQualityAnalyzer.analyzeGrayMat(gray)
        if (quality.blurVariance < minBlurScore) {
            reset()
            return false
        }

        val prev = lastCorners
        lastCorners = corners
        if (prev == null || !isStable(prev, corners)) {
            stableCount = 1
            return false
        }

        stableCount++
        return stableCount >= requiredStableFrames
    }

    fun reset() {
        stableCount = 0
        lastCorners = null
    }

    private fun isStable(a: Array<Point>, b: Array<Point>): Boolean {
        for (i in a.indices) {
            val dx = a[i].x - b[i].x
            val dy = a[i].y - b[i].y
            if (kotlin.math.sqrt(dx * dx + dy * dy) > maxCornerJitterPx) return false
        }
        return true
    }

    private fun polygonArea(pts: Array<Point>): Double {
        var sum = 0.0
        for (i in pts.indices) {
            val p1 = pts[i]
            val p2 = pts[(i + 1) % pts.size]
            sum += p1.x * p2.y - p2.x * p1.y
        }
        return abs(sum) / 2.0
    }

    private fun detectQuad(gray: Mat): Array<Point>? {
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(blurred, edges, 60.0, 160.0)
        Imgproc.dilate(edges, edges, Mat())

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        var best: Array<Point>? = null
        var bestArea = 0.0
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < gray.rows() * gray.cols() * 0.1) continue
            val contour2f = MatOfPoint2f(*contour.toArray())
            val peri = Imgproc.arcLength(contour2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)
            if (approx.toArray().size == 4 && area > bestArea) {
                bestArea = area
                best = approx.toArray()
            }
        }
        // approxPolyDP doesn't guarantee the same starting vertex or winding
        // direction between two frames of the same physical page, so without
        // this, isStable() could compare corner[0] of one frame against a
        // different physical corner in the next and read false jitter.
        // Order to [topLeft, topRight, bottomRight, bottomLeft] every time so
        // corner i always means the same physical corner across frames.
        return best?.let { orderCorners(it) }
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
}
