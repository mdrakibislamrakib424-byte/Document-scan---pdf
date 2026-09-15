package com.rakib.docscanner

/**
 * Given a list of (start, end) integer intervals along one axis, merges
 * whatever overlaps or nearly touches into bands and reports which
 * original indices fall into each band, band by band along the axis.
 *
 * This one small piece of geometry — "project things onto an axis, merge
 * what's touching, cut at the real gaps" — turns out to answer two
 * different-looking problems in this app:
 *
 * - [ReadingOrder] uses it to find whether a page's regions form separate
 *   horizontal bands (stacked content) or vertical bands (side-by-side
 *   columns), to recover reading order.
 * - [TableStructureRecovery] uses it to find a table's actual *columns* —
 *   OCR'd words whose left/right edges line up into the same vertical band
 *   across many rows are, by definition, sitting in the same column.
 *
 * Written once here rather than twice (slightly differently, and with
 * twice the chance of a subtle bug) in each of those two files.
 */
object IntervalClustering {

    /**
     * @param minGapFraction how wide a gap must be, as a fraction of the
     *   total span covered by every interval combined, before it counts as
     *   a real boundary rather than incidental spacing. Larger values
     *   merge more aggressively (fewer, wider bands); smaller values split
     *   more readily (more, narrower bands). Different callers want
     *   different sensitivity here — a page's columns are usually a wide,
     *   obvious gap; a table's columns can be a much narrower one — so
     *   this isn't a single shared constant, each caller picks its own.
     * @return groups of original-list indices, one group per band, in
     *   ascending order along the axis (so band 0 is the topmost/leftmost).
     */
    fun cluster(intervals: List<Pair<Int, Int>>, minGapFraction: Float): List<List<Int>> {
        if (intervals.isEmpty()) return emptyList()

        val totalSpan = (intervals.maxOf { it.second } - intervals.minOf { it.first }).coerceAtLeast(1)
        val minGap = (totalSpan * minGapFraction).toInt().coerceAtLeast(1)

        data class Band(var start: Int, var end: Int, val indices: MutableList<Int>)

        val sortedByStart = intervals.withIndex().sortedBy { it.value.first }
        val bands = mutableListOf<Band>()
        for ((index, interval) in sortedByStart) {
            val (start, end) = interval
            val last = bands.lastOrNull()
            if (last != null && start - last.end < minGap) {
                last.end = maxOf(last.end, end)
                last.indices.add(index)
            } else {
                bands.add(Band(start, end, mutableListOf(index)))
            }
        }
        return bands.map { it.indices }
    }
}
