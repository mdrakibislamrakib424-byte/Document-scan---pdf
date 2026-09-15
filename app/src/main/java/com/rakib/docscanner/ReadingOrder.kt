package com.rakib.docscanner

/**
 * PP-DocLayout-S (unlike the heavier PP-DocLayoutV3) reports *only* boxes
 * and classes — no reading-order head. Left to a naive top-to-bottom sort,
 * a genuinely two-column exam or magazine page would interleave the left
 * and right columns line-by-line, which is worse than not detecting
 * columns at all.
 *
 * This implements recursive XY-cut — the standard classical algorithm for
 * this exact problem (Ha, Haralick & Phillips 1995; also what PP-Structure
 * itself falls back on for ordering when a model doesn't supply one
 * directly): repeatedly try to slice the page through a gap that no
 * region crosses, alternating between a horizontal slice (stacked bands —
 * e.g. a heading above a two-column body) and, when no horizontal gap
 * exists, a vertical slice (side-by-side columns). Each slice is recursed
 * into independently, so a page can mix stacked bands and side-by-side
 * columns at different levels, which is exactly what real documents do.
 */
object ReadingOrder {

    /** Minimum gap, as a fraction of the cut axis's total span, to treat as a real column/band boundary rather than noise. */
    private const val MIN_GAP_FRACTION = 0.02f

    fun sort(regions: List<LayoutRegion>): List<LayoutRegion> {
        if (regions.size <= 1) return regions
        return xyCut(regions, horizontalFirst = true)
    }

    private fun xyCut(regions: List<LayoutRegion>, horizontalFirst: Boolean): List<LayoutRegion> {
        if (regions.size <= 1) return regions

        val primaryGroups = if (horizontalFirst) {
            splitOnGaps(regions, axis = Axis.Y)
        } else {
            splitOnGaps(regions, axis = Axis.X)
        }

        if (primaryGroups.size > 1) {
            // A clean slice was found: recurse into each piece, trying the
            // *other* axis first inside it (a horizontal band's contents
            // are checked for columns; a column's contents are checked for
            // sub-bands), then concatenate in slice order.
            return primaryGroups.flatMap { xyCut(it, horizontalFirst = !horizontalFirst) }
        }

        // No gap on the preferred axis — try the other one before giving up.
        val secondaryGroups = if (horizontalFirst) {
            splitOnGaps(regions, axis = Axis.X)
        } else {
            splitOnGaps(regions, axis = Axis.Y)
        }
        if (secondaryGroups.size > 1) {
            return secondaryGroups.flatMap { xyCut(it, horizontalFirst = horizontalFirst) }
        }

        // Neither axis cuts cleanly — this is a leaf cluster (or an
        // irregular/overlapping mess the model produced). Fall back to a
        // plain top-then-left ordering rather than looping forever.
        return regions.sortedWith(compareBy({ it.top }, { it.left }))
    }

    private enum class Axis { X, Y }

    /**
     * Projects every region's interval onto [axis] and delegates the
     * actual merge-and-gap-detect work to [IntervalClustering] — this
     * function's only job is picking which edges of a region matter for
     * this axis and translating band-of-indices back into bands of
     * [LayoutRegion]s.
     */
    private fun splitOnGaps(regions: List<LayoutRegion>, axis: Axis): List<List<LayoutRegion>> {
        val intervals = regions.map { r ->
            when (axis) {
                Axis.Y -> r.top to r.bottom
                Axis.X -> r.left to r.right
            }
        }
        val groups = IntervalClustering.cluster(intervals, MIN_GAP_FRACTION)
        if (groups.size <= 1) return listOf(regions)
        return groups.map { indices -> indices.map { regions[it] } }
    }
}
