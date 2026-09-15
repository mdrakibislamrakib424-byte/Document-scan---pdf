package com.rakib.docscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.File
import java.io.FileOutputStream

/**
 * Holds the pages of the document currently being assembled — the thing
 * "Batch continuous scan" and "reorder / delete / rotate" from the roadmap
 * actually operate on.
 *
 * Each confirmed page (the output of ReviewActivity) is written to a file
 * under the app's cache dir immediately, rather than kept as an in-memory
 * Bitmap list. Reasons this matters for correctness, not just performance:
 *  - A dozen full-resolution page bitmaps held in memory at once is a real
 *    OutOfMemoryError risk on a phone, regardless of the "don't worry about
 *    performance" instruction — that instruction is about output quality,
 *    not about crashing the app.
 *  - Writing to disk means the session survives the OS killing the app in
 *    the background mid-scan (a very normal thing to happen between
 *    photographing page 3 and page 4 of a document).
 *
 * This is a process-lifetime singleton (not persisted across app restarts
 * after the process is fully killed) — "resume an old in-progress scan
 * after force-closing the app" is a further feature, not assumed here.
 */
object PageSession {

    data class PageItem(
        val id: String,
        var filePath: String,
        var rotationDegrees: Int = 0 // 0, 90, 180, 270 — applied on top of the stored file
    )

    private val pages = mutableListOf<PageItem>()
    private var nextId = 0

    fun pageCount(): Int = pages.size

    fun getPages(): List<PageItem> = pages.toList()

    /** Saves the bitmap to a session file and appends it as the newest page. */
    fun addPage(context: Context, bitmap: Bitmap): PageItem {
        val dir = sessionDir(context)
        // PNG (lossless), not JPEG — per Rakib's explicit priority of quality
        // over file size. A JPEG re-encode here would introduce a second
        // generation of compression artifacts before the page even reaches
        // the final PDF (Standard export draws it losslessly; Compressed
        // export makes its own single, intentional JPEG pass at export
        // time — see PdfExporter). Storing losslessly at every intermediate
        // step means only the choices the user actually asks for affect
        // final quality, not incidental cache-format decisions.
        val file = File(dir, "page_${System.currentTimeMillis()}_${nextId++}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        val item = PageItem(id = file.name, filePath = file.absolutePath)
        pages.add(item)
        return item
    }

    fun removePage(id: String) {
        val item = pages.find { it.id == id } ?: return
        File(item.filePath).delete()
        pages.remove(item)
    }

    fun rotatePage(id: String, deltaDegrees: Int) {
        val item = pages.find { it.id == id } ?: return
        item.rotationDegrees = ((item.rotationDegrees + deltaDegrees) % 360 + 360) % 360
    }

    /** Reorders by moving the page at [fromIndex] to [toIndex] (RecyclerView drag callback). */
    fun movePage(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        if (fromIndex !in pages.indices || toIndex !in pages.indices) return
        val item = pages.removeAt(fromIndex)
        pages.add(toIndex, item)
    }

    /** Loads a page's bitmap from disk with its stored rotation applied. */
    fun loadBitmap(item: PageItem): Bitmap {
        val decoded = BitmapFactory.decodeFile(item.filePath)
            ?: throw IllegalStateException("Page file missing: ${item.filePath}")
        if (item.rotationDegrees == 0) return decoded
        val matrix = Matrix().apply { postRotate(item.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    /** Clears the current session (call after a successful export, or to start fresh). */
    fun clear() {
        for (item in pages) File(item.filePath).delete()
        pages.clear()
    }

    private fun sessionDir(context: Context): File {
        val dir = File(context.cacheDir, "session")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
