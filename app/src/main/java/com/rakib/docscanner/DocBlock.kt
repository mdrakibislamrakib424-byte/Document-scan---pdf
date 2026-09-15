package com.rakib.docscanner

import android.graphics.Bitmap

/**
 * One piece of content on the way into [RetypesetRenderer], already
 * classified into what it *is* rather than just "some OCR'd text".
 *
 * This is what Phase 5.5's `List<String>` of flat paragraphs grew into
 * once a layout model (Phase 6) is available: instead of one undivided
 * bag of paragraph text, the renderer gets a typed sequence of blocks in
 * reading order and can treat a title, a table, and a photo differently
 * — which was exactly the gap the Phase 5.5 README called out ("there is
 * no layout-analysis model here").
 */
sealed class DocBlock {
    /** The document's own title (PP-DocLayout-S's `doc_title` class) — rendered larger than a section [Title]. */
    data class MainTitle(val text: String) : DocBlock()

    /** A section/figure/table heading — a short bold line introducing what follows. */
    data class Title(val text: String) : DocBlock()

    /** Ordinary reflowable prose — laid out exactly as Phase 5.5 already did. */
    data class Paragraph(val text: String) : DocBlock()

    /**
     * A table region, recovered as an actual row/column grid (see
     * [TableStructureEngine]) — [rows] is a list of rows, each the same
     * length (one string per column; empty where a cell had no text).
     * [RetypesetRenderer] draws this with real grid lines and per-column
     * widths, not just shaded stacked text.
     */
    data class Table(val rows: List<List<String>>) : DocBlock()

    /**
     * A photo, chart, or seal/stamp region. The crop is carried as an
     * actual bitmap to be drawn into the output — not OCR'd, since running
     * text recognition on a photograph produces noise, not a caption.
     */
    data class Image(val bitmap: Bitmap) : DocBlock()
}
