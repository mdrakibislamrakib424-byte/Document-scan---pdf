package com.rakib.docscanner

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView

class PageAdapter(
    private val onRotate: (PageSession.PageItem) -> Unit,
    private val onDelete: (PageSession.PageItem) -> Unit,
    private val onReorder: (fromIndex: Int, toIndex: Int) -> Unit
) : RecyclerView.Adapter<PageAdapter.PageViewHolder>() {

    private var items: MutableList<PageSession.PageItem> = mutableListOf()

    fun submitList(newItems: List<PageSession.PageItem>) {
        items = newItems.toMutableList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_page, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val item = items[position]
        holder.pageNumber.text = "Page ${position + 1}"
        holder.thumbnail.setImageBitmap(decodeThumbnail(item))
        holder.btnRotate.setOnClickListener { onRotate(item) }
        holder.btnDelete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount(): Int = items.size

    /**
     * Downsampled decode purely for the list thumbnail — the on-disk page
     * file (used at export time) is untouched at full resolution. This is
     * a memory-safety measure (a session could hold dozens of pages), not
     * a quality tradeoff on the actual scan output.
     */
    private fun decodeThumbnail(item: PageSession.PageItem): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
        val decoded = BitmapFactory.decodeFile(item.filePath, opts) ?: return null
        if (item.rotationDegrees == 0) return decoded
        val matrix = Matrix().apply { postRotate(item.rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }

    /** Called by the ItemTouchHelper callback while the user drags a row. */
    fun onDragMove(fromPosition: Int, toPosition: Int) {
        val item = items.removeAt(fromPosition)
        items.add(toPosition, item)
        notifyItemMoved(fromPosition, toPosition)
        onReorder(fromPosition, toPosition)
    }

    class PageViewHolder(itemView: android.view.View) : RecyclerView.ViewHolder(itemView) {
        val thumbnail: ImageView = itemView.findViewById(R.id.ivThumbnail)
        val pageNumber: TextView = itemView.findViewById(R.id.tvPageNumber)
        val btnRotate: ImageButton = itemView.findViewById(R.id.btnRotate)
        val btnDelete: ImageButton = itemView.findViewById(R.id.btnDelete)
    }
}

/** Enables drag-to-reorder on the page RecyclerView (no swipe-to-delete — there's an explicit delete button). */
class PageTouchHelperCallback(private val adapter: PageAdapter) : ItemTouchHelper.Callback() {

    override fun isLongPressDragEnabled(): Boolean = true
    override fun isItemViewSwipeEnabled(): Boolean = false

    override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
        val dragFlags = ItemTouchHelper.UP or ItemTouchHelper.DOWN
        return makeMovementFlags(dragFlags, 0)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        source: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean {
        adapter.onDragMove(source.bindingAdapterPosition, target.bindingAdapterPosition)
        return true
    }

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        // Swiping is disabled; nothing to do.
    }
}
