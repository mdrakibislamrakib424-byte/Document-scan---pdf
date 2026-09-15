package com.rakib.docscanner

import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer

object FrameUtils {

    /**
     * Auto-capture needs to run on every preview frame, so unlike the rest
     * of the pipeline (which deliberately favors quality over speed per
     * Rakib's instruction), this one path stays cheap on purpose — it's
     * not producing the final scan, just deciding *when* to press the
     * shutter for you. The Y plane alone (luminance) is enough for edge
     * detection and blur scoring; skipping U/V avoids a full YUV->RGB
     * conversion 30 times a second.
     */
    fun yPlaneToGrayMat(image: ImageProxy): Mat {
        val yPlane = image.planes[0]
        val buffer: ByteBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val width = image.width
        val height = image.height

        val data = ByteArray(buffer.remaining())
        buffer.get(data)

        return if (rowStride == width) {
            val mat = Mat(height, width, CvType.CV_8UC1)
            mat.put(0, 0, data)
            mat
        } else {
            // Row stride has padding — copy row by row into a tight buffer.
            val tight = ByteArray(width * height)
            for (row in 0 until height) {
                System.arraycopy(data, row * rowStride, tight, row * width, width)
            }
            val mat = Mat(height, width, CvType.CV_8UC1)
            mat.put(0, 0, tight)
            mat
        }
    }
}
