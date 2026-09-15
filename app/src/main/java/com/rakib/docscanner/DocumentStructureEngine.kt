package com.rakib.docscanner

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI

/**
 * The result of [DocumentStructureEngine.buildDocument]: the flat,
 * reading-order block sequence for [RetypesetRenderer], plus whatever
 * running header/footer text was found. Header and footer are pulled out
 * separately rather than left as blocks in the main sequence because
 * they're page furniture, not document content — see the field docs below
 * for the specific editorial call being made.
 */
data class StructuredDocument(
    val blocks: List<DocBlock>,
    /**
     * The first non-blank `header`-class region OCR'd across the whole
     * batch, or null if none was found. A repeated running header (a
     * chapter title, say) is treated as one piece of document-level
     * metadata — shown once per *output* page by [RetypesetRenderer] —
     * rather than re-inserted once per *original* photo, since a
     * retypeset document is repaginated anyway and the original header's
     * position on the old pages means nothing in the new layout.
     */
    val headerText: String?,
    /** Same idea as [headerText], for the `footer`-class region. */
    val footerText: String?
)

/**
 * Turns a list of page bitmaps into a [StructuredDocument] for
 * [RetypesetRenderer] — the Phase 6 replacement for Phase 5.5's
 * `bitmaps.flatMap { engine.recognize(it).paragraphs }`.
 *
 * Falls back to that exact old behaviour (flat full-page OCR, everything
 * treated as an ordinary paragraph) whenever the layout model isn't
 * available — [layoutEngine] is null because it failed to load — or when
 * it runs but finds nothing on a given page. That fallback is deliberately
 * scoped *per page*, not to the whole document: one odd page (a photo
 * that confuses the layout model, say) shouldn't drag every other page
 * in the same batch down to the plain-text treatment.
 */
object DocumentStructureEngine {

    /** Expands each region's crop slightly beyond its detected box, since a tight box can clip ascenders/descenders right at the edge. */
    private const val REGION_CROP_PADDING_PX = 6

    /**
     * A region crop shorter than this is treated as "small text" and
     * upscaled (classical Lanczos + sharpen — see
     * [Phase2Processor.upscale]; genuinely no AI super-resolution model is
     * involved, stated plainly rather than oversold) before OCR runs on
     * it. This is a height threshold, not a resolution ratio, because
     * what actually hurts Tesseract's accuracy is small *absolute* glyph
     * size regardless of how large the original photo was — a footnote or
     * a caption crop can be tiny even from an otherwise high-resolution
     * scan. Chosen as a reasonable engineering estimate, not a tuned
     * value — there was no way to empirically test this without a device.
     */
    private const val SMALL_REGION_HEIGHT_PX = 90
    private const val SMALL_REGION_UPSCALE_FACTOR = 2.0

    fun buildDocument(
        context: Context,
        pages: List<Bitmap>,
        layoutEngine: LayoutEngine?,
        ocrEngine: TextOcrEngine
    ): StructuredDocument {
        val allBlocks = mutableListOf<DocBlock>()
        var headerText: String? = null
        var footerText: String? = null

        for (page in pages) {
            val pageResult = if (layoutEngine == null) {
                PageResult(flatPageBlocks(page, ocrEngine), null, null)
            } else {
                try {
                    val regions = layoutEngine.analyze(page)
                    if (regions.isEmpty()) {
                        PageResult(flatPageBlocks(page, ocrEngine), null, null)
                    } else {
                        structuredPageBlocks(context, page, regions, ocrEngine)
                    }
                } catch (e: Exception) {
                    // A layout failure on one page shouldn't take down the
                    // whole document — that page just falls back to the
                    // plain-text treatment the rest of the document would
                    // have gotten if the layout model weren't available
                    // at all.
                    PageResult(flatPageBlocks(page, ocrEngine), null, null)
                }
            }
            allBlocks.addAll(pageResult.blocks)
            if (headerText == null && !pageResult.header.isNullOrBlank()) headerText = pageResult.header
            if (footerText == null && !pageResult.footer.isNullOrBlank()) footerText = pageResult.footer
        }
        return StructuredDocument(allBlocks, headerText, footerText)
    }

    private data class PageResult(val blocks: List<DocBlock>, val header: String?, val footer: String?)

    /** The old Phase 5.5 behaviour: OCR the whole page, keep only the engine's own paragraph segmentation. */
    private fun flatPageBlocks(page: Bitmap, ocrEngine: TextOcrEngine): List<DocBlock> {
        return ocrEngine.recognize(page, TessBaseAPI.PageSegMode.PSM_AUTO).paragraphs
            .filter { it.isNotBlank() }
            .map { DocBlock.Paragraph(it) }
    }

    private fun structuredPageBlocks(
        context: Context,
        page: Bitmap,
        regions: List<LayoutRegion>,
        ocrEngine: TextOcrEngine
    ): PageResult {
        val blocks = mutableListOf<DocBlock>()
        var header: String? = null
        var footer: String? = null

        for (region in regions) {
            when (region.layoutClass) {
                LayoutClass.HEADER -> {
                    val crop = cropRegion(page, region) ?: continue
                    val text = collapseWhitespace(ocrCrop(ocrEngine, crop, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK).fullText)
                    if (text.isNotEmpty()) header = text
                    crop.recycle()
                    continue
                }
                LayoutClass.FOOTER -> {
                    val crop = cropRegion(page, region) ?: continue
                    val text = collapseWhitespace(ocrCrop(ocrEngine, crop, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK).fullText)
                    if (text.isNotEmpty()) footer = text
                    crop.recycle()
                    continue
                }
                else -> Unit
            }

            if (region.role == BlockRole.SKIP) continue
            val crop = cropRegion(page, region) ?: continue

            when (region.role) {
                BlockRole.IMAGE -> {
                    // Not recycled — RetypesetRenderer draws this bitmap
                    // directly into the output document.
                    blocks.add(DocBlock.Image(crop))
                }
                BlockRole.TABLE -> {
                    val cropResult = ocrCropWithBitmap(ocrEngine, crop, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
                    // TableStructureEngine needs the *same* bitmap the word
                    // boxes were measured against — ocrCropWithBitmap may
                    // have silently upscaled a small crop before OCR (see
                    // its kdoc), so that's cropResult.bitmapUsed, not
                    // necessarily this branch's own `crop`.
                    val grid = TableStructureEngine.recognize(context, cropResult.bitmapUsed, cropResult.result.words)
                    if (grid.cells.isNotEmpty()) {
                        blocks.add(DocBlock.Table(grid.cells))
                    } else {
                        // No word-box data to build a grid from (rare —
                        // e.g. hOCR gave lines but not clean word boxes).
                        // Falls back to one column per line rather than
                        // silently dropping the table's text.
                        val rows = cropResult.result.lines.ifEmpty { cropResult.result.paragraphs }.filter { it.isNotBlank() }
                        if (rows.isNotEmpty()) blocks.add(DocBlock.Table(rows.map { listOf(it) }))
                    }
                    if (cropResult.bitmapUsed !== crop) cropResult.bitmapUsed.recycle()
                    crop.recycle()
                }
                BlockRole.MAIN_TITLE, BlockRole.TITLE -> {
                    val result = ocrCrop(ocrEngine, crop, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
                    val text = collapseWhitespace(result.fullText)
                    if (text.isNotEmpty()) {
                        blocks.add(if (region.role == BlockRole.MAIN_TITLE) DocBlock.MainTitle(text) else DocBlock.Title(text))
                    }
                    crop.recycle()
                }
                BlockRole.PARAGRAPH -> {
                    val result = ocrCrop(ocrEngine, crop, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK)
                    val paragraphs = result.paragraphs.filter { it.isNotBlank() }.ifEmpty {
                        collapseWhitespace(result.fullText).takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
                    }
                    paragraphs.forEach { blocks.add(DocBlock.Paragraph(it)) }
                    crop.recycle()
                }
                BlockRole.SKIP -> Unit // unreachable — filtered above
            }
        }
        return PageResult(blocks, header, footer)
    }

    private data class OcrCropResult(val bitmapUsed: Bitmap, val result: OcrPageResult)

    /**
     * OCRs one region crop, upscaling it first if it's short enough that
     * small glyph size would otherwise hurt recognition (see
     * [SMALL_REGION_HEIGHT_PX]). Centralized here so every OCR call site
     * in this file gets the same treatment instead of repeating the
     * height check five times.
     *
     * [pageSegMode] is `Int`, not `TessBaseAPI.PageSegMode` — that nested
     * class is a holder of `int` constants (Android's `@IntDef` pattern),
     * not an instantiable type; see [OcrEngine.recognize]'s kdoc.
     *
     * Returns the bitmap actually fed to Tesseract alongside the result —
     * when a crop gets upscaled, [OcrPageResult]'s word boxes are in the
     * *upscaled* bitmap's coordinate space, not the original crop's. Most
     * callers only want the text and can ignore that (see the plain
     * [ocrCrop] overload below); [TableStructureEngine] specifically needs
     * geometry, so it has to know which bitmap that geometry actually
     * matches.
     */
    private fun ocrCropWithBitmap(ocrEngine: TextOcrEngine, crop: Bitmap, pageSegMode: Int): OcrCropResult {
        if (crop.height >= SMALL_REGION_HEIGHT_PX) {
            return OcrCropResult(crop, ocrEngine.recognize(crop, pageSegMode))
        }
        val upscaled = try {
            Phase2Processor.upscale(crop, SMALL_REGION_UPSCALE_FACTOR)
        } catch (e: Exception) {
            null // OpenCV hiccup on this crop — OCR the original rather than losing the region entirely.
        }
        if (upscaled == null) return OcrCropResult(crop, ocrEngine.recognize(crop, pageSegMode))
        return OcrCropResult(upscaled, ocrEngine.recognize(upscaled, pageSegMode))
    }

    /** Plain-text convenience wrapper over [ocrCropWithBitmap] for call sites that don't need geometry — recycles any upscaled intermediate bitmap on the way out since the caller never sees it. */
    private fun ocrCrop(ocrEngine: TextOcrEngine, crop: Bitmap, pageSegMode: Int): OcrPageResult {
        val cropResult = ocrCropWithBitmap(ocrEngine, crop, pageSegMode)
        if (cropResult.bitmapUsed !== crop) cropResult.bitmapUsed.recycle()
        return cropResult.result
    }

    private fun collapseWhitespace(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun cropRegion(page: Bitmap, region: LayoutRegion): Bitmap? {
        val left = (region.left - REGION_CROP_PADDING_PX).coerceIn(0, (page.width - 1).coerceAtLeast(0))
        val top = (region.top - REGION_CROP_PADDING_PX).coerceIn(0, (page.height - 1).coerceAtLeast(0))
        val right = (region.right + REGION_CROP_PADDING_PX).coerceIn(left + 1, page.width)
        val bottom = (region.bottom + REGION_CROP_PADDING_PX).coerceIn(top + 1, page.height)
        if (right <= left || bottom <= top) return null
        return try {
            Bitmap.createBitmap(page, left, top, right - left, bottom - top)
        } catch (e: Exception) {
            null
        }
    }
}
