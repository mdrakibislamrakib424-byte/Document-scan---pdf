package com.rakib.docscanner

/**
 * NOTE (found during review): this class is no longer called from
 * anywhere in the app — [DocumentStructureEngine] was wired to
 * [TableStructureEngine] instead (real ruled-line detection, with this
 * class's word-clustering approach kept there as its own fallback for
 * borderless tables). Left in place rather than deleted since deleting
 * working, tested-by-reasoning code during a review isn't this pass's
 * call to make — but if nothing comes to depend on it, it's a candidate
 * for removal.
 *
 * Recovers a table's actual row/column grid from OCR'd word positions.
 *
 * This is the piece that turns "a table region's text, kept in row order"
 * (what Phase 6 shipped with) into "an actual table" (rows *and* columns).
 * It deliberately does **not** wire up PaddleOCR's own table-structure
 * model (SLANet / SLANet-plus / SLANeXt) — worth explaining why, since a
 * real ONNX export of that model does exist and was found during research
 * for this. That model is a sequence-to-sequence decoder: it doesn't
 * output a grid directly, it outputs a sequence of HTML structure tokens
 * (`<tr>`, `<td>`, `<td colspan="2">`, ...) from a fixed, PaddleOCR-internal
 * vocabulary, each aligned to a predicted cell quadrilateral. Getting that
 * right requires the *exact* token vocabulary file and decode order — and
 * getting it wrong wouldn't fail loudly, it would silently produce a
 * plausible-looking but *wrong* grid (cells merged or split incorrectly).
 * That's a meaningfully different, larger risk than PP-DocLayout-S's
 * contract (a plain fixed-shape detection tensor, checkable by shape
 * alone) — and this environment still can't run either one to check.
 *
 * So instead: this uses data the app already has and already trusts —
 * Tesseract's own per-word bounding boxes (already used for the
 * searchable-PDF text layer since Phase 5) — and a classical, fully
 * inspectable geometric method: rows come from Tesseract's own line
 * segmentation (already reliable — that's its actual job), and columns
 * are recovered by clustering every word's left/right edges across the
 * *whole* table at once, via [IntervalClustering] — words whose edges line
 * up into the same vertical band, across many rows, are in the same
 * column by definition. This is the same principle real classical
 * table-extraction tools (e.g. Camelot's "stream" mode) use when a
 * learned model isn't available.
 *
 * Honest limitation: this recovers a table's *visual* grid, not its
 * logical one. A genuinely merged cell (colspan/rowspan in the original)
 * has no way to signal that through word positions alone, so it comes out
 * as one cell repeated blank in the columns it doesn't actually span
 * (rather than one wide cell) — visually close, not identical.
 */
object TableStructureRecovery {

    // Deliberately much smaller than ReadingOrder's 0.02 for page-level
    // columns: a table's own columns can be close together, and the
    // priority here is not merging two real columns into one over
    // splitting one column's normal inter-word spacing into two —
    // an over-split column still reads correctly (just as two adjacent
    // narrow columns), while an under-split one loses the row/column
    // structure entirely.
    private const val COLUMN_GAP_FRACTION = 0.035f

    /**
     * @param lineWords one entry per OCR'd text line inside the table
     *   region (see [HocrParser.parseLineWords]), each holding that
     *   line's words with their pixel bounding boxes, in reading order.
     * @return a grid — one list per row, each the same length (one entry
     *   per recovered column; empty string where no word landed in that
     *   cell). Empty if there were no words to work with.
     */
    fun recover(lineWords: List<List<OcrWord>>): List<List<String>> {
        val allWords = mutableListOf<OcrWord>()
        val lineStartIndex = IntArray(lineWords.size)
        for ((i, line) in lineWords.withIndex()) {
            lineStartIndex[i] = allWords.size
            allWords.addAll(line)
        }
        if (allWords.isEmpty()) return emptyList()

        val columnGroups = IntervalClustering.cluster(allWords.map { it.left to it.right }, COLUMN_GAP_FRACTION)
        if (columnGroups.isEmpty()) return emptyList()

        val columnOfIndex = IntArray(allWords.size) { -1 }
        for ((columnIndex, group) in columnGroups.withIndex()) {
            for (wordIndex in group) columnOfIndex[wordIndex] = columnIndex
        }
        val columnCount = columnGroups.size

        val grid = mutableListOf<List<String>>()
        for ((lineIndex, line) in lineWords.withIndex()) {
            val base = lineStartIndex[lineIndex]
            val cells = MutableList(columnCount) { StringBuilder() }
            // Within one line, place words left-to-right so a multi-word
            // cell's text reads in the right order.
            val orderedLocalIndices = line.indices.sortedBy { line[it].left }
            for (localIndex in orderedLocalIndices) {
                val globalIndex = base + localIndex
                val column = columnOfIndex[globalIndex]
                if (column !in 0 until columnCount) continue
                if (cells[column].isNotEmpty()) cells[column].append(' ')
                cells[column].append(line[localIndex].text)
            }
            grid.add(cells.map { it.toString() })
        }
        return grid
    }
}
