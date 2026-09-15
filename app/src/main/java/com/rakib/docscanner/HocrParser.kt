package com.rakib.docscanner

/**
 * Tesseract's getHOCRText() returns hOCR — HTML with each word wrapped like:
 *   <span class='ocrx_word' id='word_1_1' title='bbox 34 22 187 45; x_wconf 96'>Hello</span>
 *
 * This pulls out exactly what the searchable-PDF text layer needs: the
 * word string, its pixel bounding box (in the coordinate space of the
 * bitmap that was OCR'd), and the confidence score. It's a small, targeted
 * regex parser rather than a full HTML/XML parser because hOCR's word
 * spans are a single well-defined, non-nested pattern — pulling in a full
 * XML parser dependency for this one pattern isn't warranted.
 */
object HocrParser {

    private val WORD_REGEX = Regex(
        """<span class='ocrx_word'[^>]*title='bbox (\d+) (\d+) (\d+) (\d+);\s*x_wconf (\d+)'[^>]*>(.*?)</span>""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun parseWords(hocr: String): List<OcrWord> {
        val results = mutableListOf<OcrWord>()
        for (match in WORD_REGEX.findAll(hocr)) {
            val (left, top, right, bottom, conf, rawText) = match.destructured
            val text = unescapeHtml(rawText).trim()
            if (text.isEmpty()) continue
            results.add(
                OcrWord(
                    text = text,
                    left = left.toInt(),
                    top = top.toInt(),
                    right = right.toInt(),
                    bottom = bottom.toInt(),
                    confidence = conf.toFloatOrNull() ?: 0f
                )
            )
        }
        return results
    }

    private val PARAGRAPH_REGEX = Regex(
        """<p class='ocr_par'[^>]*>(.*?)</p>""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val WORD_TEXT_ONLY_REGEX = Regex(
        """<span class='ocrx_word'[^>]*>(.*?)</span>""",
        RegexOption.DOT_MATCHES_ALL
    )

    /**
     * Groups OCR'd words into paragraphs using Tesseract's own page
     * segmentation (its hOCR output wraps each paragraph it detected in
     * `<p class='ocr_par'>`). This deliberately ignores original line
     * breaks *within* a paragraph — Phase 5.5 re-flows text into a new
     * layout anyway, so what matters is "where does one paragraph end and
     * the next begin", not exactly where Tesseract wrapped each line.
     *
     * Honest limitation: paragraph detection is only as good as
     * Tesseract's own page segmentation. A page with an unusual layout
     * (multi-column, mixed text/table) can get merged or split
     * incorrectly. There's no ground truth to correct this against short
     * of a dedicated layout-analysis model, which is a heavier addition.
     */
    fun parseParagraphs(hocr: String): List<String> {
        val paragraphs = mutableListOf<String>()
        for (parMatch in PARAGRAPH_REGEX.findAll(hocr)) {
            val block = parMatch.groupValues[1]
            val words = WORD_TEXT_ONLY_REGEX.findAll(block)
                .map { unescapeHtml(it.groupValues[1]).trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (words.isNotEmpty()) paragraphs.add(words.joinToString(" "))
        }
        return paragraphs
    }

    private const val LINE_MARKER = "<span class='ocr_line'"

    /**
     * Line-level text (one string per `ocr_line`, in reading order),
     * preserving row breaks — unlike [parseParagraphs], which deliberately
     * throws line breaks away for reflowable prose. Phase 6 uses this for
     * table regions: a table's rows are meaningful structure, so a
     * digital re-typeset should keep each row on its own line rather than
     * reflowing the whole table into one run-on paragraph.
     *
     * This deliberately does *not* use a `(.*?)</span>` capture the way
     * [parseParagraphs] captures `</p>` blocks: an `ocr_line` span's own
     * closing tag is `</span>` — the exact same tag its child word spans
     * close with — so a non-greedy regex would stop at the *first* word's
     * closing tag, not the line's. Splitting the raw hOCR string on each
     * `ocr_line` marker and treating everything up to the *next* marker
     * (or end of string) as that line's segment sidesteps the problem
     * entirely: whatever trailing closing tags spill into a segment don't
     * match [WORD_TEXT_ONLY_REGEX], so they're harmless.
     *
     * A line's own text is pulled the same way a paragraph's is (join its
     * words with spaces) rather than trying to preserve inter-word
     * spacing/column alignment from the original — Tesseract's pixel
     * positions don't translate into a fixed number of monospace columns
     * without knowing the target font, so this keeps row boundaries (the
     * one unambiguous thing) and gives up on cell alignment within a row.
     */
    fun parseLines(hocr: String): List<String> {
        val starts = mutableListOf<Int>()
        var from = 0
        while (true) {
            val idx = hocr.indexOf(LINE_MARKER, from)
            if (idx == -1) break
            starts.add(idx)
            from = idx + LINE_MARKER.length
        }

        val lines = mutableListOf<String>()
        for (i in starts.indices) {
            val segmentStart = starts[i]
            val segmentEnd = if (i + 1 < starts.size) starts[i + 1] else hocr.length
            val segment = hocr.substring(segmentStart, segmentEnd)
            val words = WORD_TEXT_ONLY_REGEX.findAll(segment)
                .map { unescapeHtml(it.groupValues[1]).trim() }
                .filter { it.isNotEmpty() }
                .toList()
            if (words.isNotEmpty()) lines.add(words.joinToString(" "))
        }
        return lines
    }

    /**
     * Same line-splitting as [parseLines], but keeping each word's full
     * [OcrWord] (text + pixel bounding box + confidence) instead of
     * collapsing it to a joined string. [TableStructureRecovery] needs
     * this: recovering a table's *columns* means looking at where each
     * word's left/right edges actually sit, which plain line text throws
     * away.
     */
    fun parseLineWords(hocr: String): List<List<OcrWord>> {
        val starts = mutableListOf<Int>()
        var from = 0
        while (true) {
            val idx = hocr.indexOf(LINE_MARKER, from)
            if (idx == -1) break
            starts.add(idx)
            from = idx + LINE_MARKER.length
        }

        val lines = mutableListOf<List<OcrWord>>()
        for (i in starts.indices) {
            val segmentStart = starts[i]
            val segmentEnd = if (i + 1 < starts.size) starts[i + 1] else hocr.length
            // Reuses parseWords (already correct for bbox/confidence
            // parsing) on just this line's slice of the hOCR string,
            // rather than re-deriving word-parsing logic a second time.
            val words = parseWords(hocr.substring(segmentStart, segmentEnd))
            if (words.isNotEmpty()) lines.add(words)
        }
        return lines
    }

    private fun unescapeHtml(s: String): String {
        return s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
    }
}
