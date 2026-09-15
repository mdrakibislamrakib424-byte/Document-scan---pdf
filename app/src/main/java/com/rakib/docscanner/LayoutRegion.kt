package com.rakib.docscanner

/**
 * The 23 layout classes PP-DocLayout-S was trained on, in the exact order
 * the model's `class_id` output refers to (matches the upstream
 * `inference.yml` — verified against the ONNX export's own documentation).
 * Getting this order wrong would silently mislabel every region, so it's
 * kept as one explicit ordered list rather than scattered constants.
 */
enum class LayoutClass(val id: Int) {
    PARAGRAPH_TITLE(0),
    IMAGE(1),
    TEXT(2),
    NUMBER(3),
    ABSTRACT(4),
    CONTENT(5),
    FIGURE_TITLE(6),
    FORMULA(7),
    TABLE(8),
    TABLE_TITLE(9),
    REFERENCE(10),
    DOC_TITLE(11),
    FOOTNOTE(12),
    HEADER(13),
    ALGORITHM(14),
    FOOTER(15),
    SEAL(16),
    CHART_TITLE(17),
    CHART(18),
    FORMULA_NUMBER(19),
    HEADER_IMAGE(20),
    FOOTER_IMAGE(21),
    ASIDE_TEXT(22);

    companion object {
        private val byId = entries.associateBy { it.id }
        fun fromId(id: Int): LayoutClass? = byId[id]
    }
}

/** What the retypeset pipeline actually does with a detected region. */
enum class BlockRole { MAIN_TITLE, TITLE, PARAGRAPH, TABLE, IMAGE, SKIP }

/**
 * Collapses the 23 fine-grained PP-DocLayout-S classes down to the 6 roles
 * the app can actually act on. This mapping is a deliberate editorial
 * choice, stated plainly rather than left implicit:
 *
 * - `doc_title` gets its own MAIN_TITLE role (rendered larger than an
 *   ordinary section title) since it's the one title that appears at most
 *   once per document rather than once per section.
 * - `paragraph_title`, `figure_title`, `table_title`, `chart_title` all
 *   become ordinary TITLE blocks — they're all "a short bold line
 *   introducing what follows", just attached to different content types.
 * - `image`, `chart`, `seal` become IMAGE blocks: the crop is embedded as a
 *   picture in the output, not OCR'd — running text-recognition on a photo
 *   or a chart would produce garbage, not a caption.
 * - `header`, `footer`, `header_image`, `footer_image`, `number`,
 *   `formula_number` are SKIP: page furniture (running heads, page
 *   numbers, repeated logos) that a *clean re-typeset* shouldn't carry
 *   over, since it gets new page breaks anyway and the old numbers/footers
 *   would land at the wrong place and mean nothing.
 * - Everything else that's read as running text (`text`, `abstract`,
 *   `content`, `reference`, `footnote`, `aside_text`, `algorithm`,
 *   `formula`) becomes an ordinary PARAGRAPH block. Honest caveat: Tesseract
 *   OCR reads `formula`/`algorithm` regions as if they were plain text,
 *   which mangles anything that isn't actual words (math notation, code
 *   indentation) — there's no formula-recognition model here, just a
 *   layout detector that knows *where* a formula sits, not what it says.
 */
fun LayoutClass.toBlockRole(): BlockRole = when (this) {
    LayoutClass.DOC_TITLE -> BlockRole.MAIN_TITLE
    LayoutClass.PARAGRAPH_TITLE,
    LayoutClass.FIGURE_TITLE,
    LayoutClass.TABLE_TITLE,
    LayoutClass.CHART_TITLE -> BlockRole.TITLE
    LayoutClass.TABLE -> BlockRole.TABLE
    LayoutClass.IMAGE,
    LayoutClass.CHART,
    LayoutClass.SEAL -> BlockRole.IMAGE
    LayoutClass.HEADER,
    LayoutClass.FOOTER,
    LayoutClass.HEADER_IMAGE,
    LayoutClass.FOOTER_IMAGE,
    LayoutClass.NUMBER,
    LayoutClass.FORMULA_NUMBER -> BlockRole.SKIP
    LayoutClass.TEXT,
    LayoutClass.ABSTRACT,
    LayoutClass.CONTENT,
    LayoutClass.REFERENCE,
    LayoutClass.FOOTNOTE,
    LayoutClass.ASIDE_TEXT,
    LayoutClass.ALGORITHM,
    LayoutClass.FORMULA -> BlockRole.PARAGRAPH
}

/**
 * One detected region on a page, in the original (un-resized) bitmap's
 * pixel coordinates — PP-DocLayout-S's `scale_factor` input makes the
 * model divide its own boxes back into that space, so no rescaling is
 * needed on this side.
 */
data class LayoutRegion(
    val layoutClass: LayoutClass,
    val score: Float,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val role: BlockRole get() = layoutClass.toBlockRole()
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
}
