package com.rakib.docscanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

data class RetypesetStyle(
    val name: String,
    val fontSizeSp: Float,
    val lineSpacingMultiplier: Float,
    val paragraphSpacingDp: Float,
    val marginDp: Float,
    val backgroundColor: Int,
    val textColor: Int
) {
    companion object {
        val PLAIN = RetypesetStyle(
            name = "Plain",
            fontSizeSp = 16f,
            lineSpacingMultiplier = 1.15f,
            paragraphSpacingDp = 12f,
            marginDp = 24f,
            backgroundColor = Color.WHITE,
            textColor = Color.BLACK
        )
        val BOOK = RetypesetStyle(
            name = "Book",
            fontSizeSp = 15f,
            lineSpacingMultiplier = 1.4f,
            paragraphSpacingDp = 20f,
            marginDp = 40f,
            backgroundColor = Color.parseColor("#FBF8F2"), // warm off-white, book-page feel
            textColor = Color.parseColor("#1A1A1A")
        )
        val NOTES = RetypesetStyle(
            name = "Notes",
            fontSizeSp = 19f,
            lineSpacingMultiplier = 1.6f,
            paragraphSpacingDp = 24f,
            marginDp = 28f,
            backgroundColor = Color.WHITE,
            textColor = Color.parseColor("#0D2B4E")
        )

        fun all() = listOf(PLAIN, BOOK, NOTES)
    }
}

/**
 * Phase 5.5 laid out a flat list of paragraph strings fresh on new, clean
 * pages. Phase 6 replaced that input with [DocBlock] — a typed sequence
 * coming from [DocumentStructureEngine] — so a title, a table, and a
 * photo each get their own visual treatment. Phase 6.1 (this version)
 * goes further on two of those: [DocBlock.Table] is now drawn as an
 * actual bordered row/column grid instead of shaded stacked text lines,
 * and a running header/footer plus page numbers are stamped onto every
 * output page as a final pass — see [stampRunningHeaderFooter].
 *
 * The core paging mechanism is unchanged since Phase 5.5 and still the
 * important design choice to state plainly: this treats the *entire*
 * document as one continuous flow of blocks, not one output page per
 * original photo. Page breaks come from how much content fits at the
 * chosen style's font size — never mid-line, and never splitting a
 * table's row in half either (a table row is paginated as one atomic
 * unit — see [drawTable]).
 *
 * Honest limitation, stated plainly rather than hidden in a comment
 * nobody reads: a [DocBlock.Table]'s grid comes from
 * [TableStructureEngine] — real ruled-line detection where the table has
 * visible borders, falling back to word-position clustering (no ruling
 * lines to find) otherwise — not a learned table-structure model. Either
 * way it recovers the *visual* grid, not the logical one: a genuinely
 * merged cell (colspan/rowspan in the original) has no way to signal that
 * through geometry alone, so it comes out as a same-width column repeated
 * blank instead of one wide cell. Visually close, not pixel-identical to
 * the source.
 */
object RetypesetRenderer {

    fun renderDocument(
        blocks: List<DocBlock>,
        style: RetypesetStyle,
        pageWidthPx: Int,
        pageHeightPx: Int,
        density: Float,
        headerText: String? = null,
        footerText: String? = null,
        showPageNumbers: Boolean = true
    ): List<Bitmap> {
        val marginPx = style.marginDp * density
        val contentWidth = (pageWidthPx - 2 * marginPx).toInt().coerceAtLeast(1)
        // Two distinct quantities kept separate (a from-scratch audit found
        // them merged into one "availableHeight" in the original Phase 5.5
        // code, which silently doubled the effective bottom margin — fixed
        // in the Phase 6 audit pass):
        val pageContentHeight = pageHeightPx - 2 * marginPx      // max content height on one whole empty page
        val contentBottomY = pageHeightPx - marginPx             // absolute Y of the content area's bottom edge
        val paragraphSpacingPx = style.paragraphSpacingDp * density

        val paragraphPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = style.fontSizeSp * density
            color = style.textColor
        }
        // Titles are distinguished by weight/size alone, not a different
        // palette — so the document still reads as one coherent style
        // (Plain/Book/Notes) rather than introducing colors the user
        // didn't choose.
        val titlePaint = TextPaint(paragraphPaint).apply {
            textSize = style.fontSizeSp * density * 1.3f
            typeface = Typeface.DEFAULT_BOLD
        }
        val mainTitlePaint = TextPaint(paragraphPaint).apply {
            textSize = style.fontSizeSp * density * 1.65f
            typeface = Typeface.DEFAULT_BOLD
        }
        // Table cells: slightly smaller than body text (more of it has to
        // fit into narrow columns) but the *same* proportional font as
        // everything else — Phase 6 used a monospace font here to fake
        // column alignment through fixed character width; Phase 6.1
        // doesn't need that trick anymore since TableStructureRecovery
        // gives real column boundaries and drawTable draws real grid
        // lines, so a normal font (which looks like an actual table,
        // rather than a code listing) is the better choice now.
        val tableCellPaint = TextPaint(paragraphPaint).apply {
            textSize = style.fontSizeSp * density * 0.85f
        }
        val tableGridPaint = Paint().apply {
            isAntiAlias = true
            color = style.textColor
            alpha = 90
            this.style = Paint.Style.STROKE
            strokeWidth = (1f * density).coerceAtLeast(1f)
        }

        val pages = mutableListOf<Bitmap>()
        var currentBitmap = newPageBitmap(pageWidthPx, pageHeightPx, style.backgroundColor)
        var canvas = Canvas(currentBitmap)
        var currentY = marginPx

        fun startNewPage() {
            pages.add(currentBitmap)
            currentBitmap = newPageBitmap(pageWidthPx, pageHeightPx, style.backgroundColor)
            canvas = Canvas(currentBitmap)
            currentY = marginPx
        }

        /** Shared flow-and-paginate logic for paragraph/title text — line-boundary-safe, same mechanism since Phase 5.5. */
        fun drawFlowText(text: String, paint: TextPaint, lineSpacingMultiplier: Float) {
            if (text.isBlank()) return
            val layout = buildStaticLayout(text, paint, contentWidth, lineSpacingMultiplier)
            var drawnSoFar = 0

            while (drawnSoFar < layout.height) {
                val spaceLeftOnPage = contentBottomY - currentY
                val firstLineHeight = if (layout.lineCount > 0) layout.getLineBottom(0).toFloat() else 0f

                if (spaceLeftOnPage < firstLineHeight) {
                    startNewPage()
                    continue
                }

                val sliceEnd = lastLineBoundaryAtOrBefore(layout, drawnSoFar + spaceLeftOnPage.toInt())
                    .coerceAtMost(layout.height)
                val amountToDraw = (sliceEnd - drawnSoFar).coerceAtLeast(0)

                if (amountToDraw <= 0) {
                    startNewPage()
                    continue
                }

                canvas.save()
                canvas.clipRect(marginPx, currentY, marginPx + contentWidth, currentY + amountToDraw)
                canvas.translate(marginPx, currentY - drawnSoFar)
                layout.draw(canvas)
                canvas.restore()

                currentY += amountToDraw
                drawnSoFar = sliceEnd
            }
        }

        /** Scales an image block to the content width (or, if that would be taller than a whole page, to one page's height instead), never splitting it across pages. */
        fun drawImage(bitmap: Bitmap) {
            if (bitmap.width <= 0 || bitmap.height <= 0) return
            val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
            var drawWidth = contentWidth.toFloat()
            var drawHeight = drawWidth * aspect
            if (drawHeight > pageContentHeight) {
                drawHeight = pageContentHeight
                drawWidth = drawHeight / aspect
            }
            if (contentBottomY - currentY < drawHeight) startNewPage()

            val left = marginPx + (contentWidth - drawWidth) / 2f
            val destRect = RectF(left, currentY, left + drawWidth, currentY + drawHeight)
            canvas.drawBitmap(bitmap, null, destRect, null)
            currentY += drawHeight
        }

        /**
         * Draws an actual bordered table: per-column widths sized to fit
         * each column's widest cell (proportionally scaled to exactly fill
         * [contentWidth]), grid lines between every row and column, and
         * each row paginated as one atomic unit — a row is never split
         * across a page break, though a single row taller than a whole
         * page's content height (an extreme, unusual case) will still
         * overflow past the bottom margin on the page it starts on rather
         * than being cut into two pages mid-row.
         */
        fun drawTable(rows: List<List<String>>) {
            if (rows.isEmpty()) return
            val columnCount = rows.maxOf { it.size }
            if (columnCount == 0) return

            val cellHPad = 6f * density
            val cellVPad = 4f * density
            val minColumnWidth = 40f * density

            val idealWidths = FloatArray(columnCount) { minColumnWidth }
            for (row in rows) {
                for (c in row.indices) {
                    val w = tableCellPaint.measureText(row[c]) + 2 * cellHPad
                    if (w > idealWidths[c]) idealWidths[c] = w
                }
            }
            val idealTotal = idealWidths.sum()
            val scale = if (idealTotal > 0f) contentWidth / idealTotal else 1f
            val columnWidths = FloatArray(columnCount) { idealWidths[it] * scale }

            // Every cell's StaticLayout is built up front so each row's
            // final height (tallest cell in that row) is known before any
            // page-break decision — the same "measure before you draw"
            // principle as the text-flow path above.
            val cellLayouts = rows.map { row ->
                (0 until columnCount).map { c ->
                    val text = row.getOrElse(c) { "" }
                    val innerWidth = (columnWidths[c] - 2 * cellHPad).toInt().coerceAtLeast(1)
                    buildStaticLayout(text, tableCellPaint, innerWidth, 1.1f)
                }
            }
            val rowHeights = cellLayouts.map { cells -> cells.maxOf { it.height } + 2 * cellVPad }

            for (r in rows.indices) {
                val rowHeight = rowHeights[r]
                if (contentBottomY - currentY < rowHeight) startNewPage()

                val rowTop = currentY
                val rowBottom = currentY + rowHeight

                canvas.drawRect(marginPx, rowTop, marginPx + contentWidth, rowBottom, tableGridPaint)

                var cellLeft = marginPx
                for (c in 0 until columnCount) {
                    val layout = cellLayouts[r][c]
                    canvas.save()
                    canvas.clipRect(cellLeft, rowTop, cellLeft + columnWidths[c], rowBottom)
                    canvas.translate(cellLeft + cellHPad, rowTop + cellVPad)
                    layout.draw(canvas)
                    canvas.restore()
                    if (c < columnCount - 1) {
                        canvas.drawLine(cellLeft + columnWidths[c], rowTop, cellLeft + columnWidths[c], rowBottom, tableGridPaint)
                    }
                    cellLeft += columnWidths[c]
                }

                currentY = rowBottom
            }
        }

        for (block in blocks) {
            when (block) {
                is DocBlock.MainTitle -> {
                    drawFlowText(block.text, mainTitlePaint, 1.2f)
                    currentY += paragraphSpacingPx * 1.3f
                }
                is DocBlock.Title -> {
                    drawFlowText(block.text, titlePaint, 1.2f)
                    currentY += paragraphSpacingPx
                }
                is DocBlock.Paragraph -> {
                    drawFlowText(block.text, paragraphPaint, style.lineSpacingMultiplier)
                    currentY += paragraphSpacingPx
                }
                is DocBlock.Table -> {
                    drawTable(block.rows)
                    currentY += paragraphSpacingPx
                }
                is DocBlock.Image -> {
                    drawImage(block.bitmap)
                    currentY += paragraphSpacingPx
                }
            }
        }

        // The last page always gets included, even if mostly empty —
        // dropping it would silently lose whatever was drawn on it.
        pages.add(currentBitmap)

        stampRunningHeaderFooter(
            pages = pages,
            headerText = headerText,
            footerText = footerText,
            showPageNumbers = showPageNumbers,
            marginPx = marginPx,
            contentWidth = contentWidth,
            density = density,
            baseColor = style.textColor
        )

        return pages
    }

    /**
     * A final pass over every finished page: draws the running header (top
     * margin band) and footer + page number (bottom margin band, on the
     * same line — footer text left, page number right — so both fit
     * within one line even on the narrowest margin style). Done as a
     * separate pass over the *completed* page list, after the main flow
     * loop, rather than threaded through [renderDocument]'s pagination —
     * "page N of M" genuinely isn't knowable until every page has already
     * been laid out, and keeping this separate means the core pagination
     * logic never has to reason about header/footer space at all (they
     * live entirely within the margin bands the body content never draws
     * into, so there's no overlap to guard against by construction).
     */
    private fun stampRunningHeaderFooter(
        pages: List<Bitmap>,
        headerText: String?,
        footerText: String?,
        showPageNumbers: Boolean,
        marginPx: Float,
        contentWidth: Int,
        density: Float,
        baseColor: Int
    ) {
        if (headerText.isNullOrBlank() && footerText.isNullOrBlank() && !showPageNumbers) return

        val bandPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = 10f * density
            color = baseColor
            alpha = 150
        }

        val totalPages = pages.size
        // The widest a page-number label will ever be for this document
        // (using the actual total, not a guess) — reserved once so the
        // footer text never has to guess how much room the number needs.
        val maxPageLabelWidth = if (showPageNumbers) bandPaint.measureText("$totalPages / $totalPages") else 0f
        val footerTextMaxWidth = (contentWidth - (if (showPageNumbers) maxPageLabelWidth + 12f * density else 0f)).coerceAtLeast(0f)

        for ((index, pageBitmap) in pages.withIndex()) {
            val pageCanvas = Canvas(pageBitmap)
            val footerBaselineY = pageBitmap.height - marginPx * 0.35f

            if (!headerText.isNullOrBlank()) {
                val shown = TextUtils.ellipsize(headerText, bandPaint, contentWidth.toFloat(), TextUtils.TruncateAt.END)
                pageCanvas.drawText(shown, 0, shown.length, marginPx, marginPx * 0.62f, bandPaint)
            }
            if (!footerText.isNullOrBlank()) {
                val shown = TextUtils.ellipsize(footerText, bandPaint, footerTextMaxWidth, TextUtils.TruncateAt.END)
                pageCanvas.drawText(shown, 0, shown.length, marginPx, footerBaselineY, bandPaint)
            }
            if (showPageNumbers) {
                val label = "${index + 1} / $totalPages"
                val labelWidth = bandPaint.measureText(label)
                pageCanvas.drawText(label, marginPx + contentWidth - labelWidth, footerBaselineY, bandPaint)
            }
        }
    }

    private fun buildStaticLayout(text: String, paint: TextPaint, width: Int, lineSpacingMultiplier: Float): StaticLayout {
        return StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setLineSpacing(0f, lineSpacingMultiplier)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    /** Largest line-bottom position at or before [targetY], so a page break never cuts a line (or a table row) in half. */
    private fun lastLineBoundaryAtOrBefore(layout: StaticLayout, targetY: Int): Int {
        var best = 0
        for (i in 0 until layout.lineCount) {
            val bottom = layout.getLineBottom(i)
            if (bottom <= targetY) best = bottom else break
        }
        return if (best == 0 && layout.lineCount > 0) layout.getLineBottom(0) else best
    }

    private fun newPageBitmap(widthPx: Int, heightPx: Int, backgroundColor: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).drawColor(backgroundColor)
        return bitmap
    }
}
