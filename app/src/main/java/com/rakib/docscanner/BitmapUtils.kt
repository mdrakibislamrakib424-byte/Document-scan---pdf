package com.rakib.docscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

object BitmapUtils {

    /**
     * Loads a full-resolution bitmap from a content:// or file:// Uri and
     * applies EXIF rotation so it's displayed the way the camera intended.
     *
     * Per Rakib's instruction, this deliberately does NOT downscale for
     * performance — quality takes priority over speed/size in this app.
     */
    fun loadOrientedBitmap(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver

        val original = resolver.openInputStream(uri).use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: throw IllegalArgumentException("Could not decode image at $uri")

        val rotationDegrees = resolver.openInputStream(uri).use { stream ->
            val exif = stream?.let { ExifInterface(it) }
            when (exif?.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }

        if (rotationDegrees == 0f) return original

        val matrix = Matrix().apply { postRotate(rotationDegrees) }
        return Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
    }
}
