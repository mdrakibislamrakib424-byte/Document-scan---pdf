package com.rakib.docscanner

import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/** A recognized table as an actual row x column grid, not just a stack of lines. */
data class TableGrid(
    val cells: List<List<String>>,
    /** How the grid was produced — used downstream to draw a confidence-appropriate border style and, if useful later, to surface to the user which tables might need a manual check. */
    val source: TableGridSource
)

enum class TableGridSource {
    /** SLANet_plus (learned model) understood real structure, including any merged cells. Highest confidence. */
    LEARNED_MODEL,
    /** Real detected ruling lines (classical fallback). No merged-cell awareness. */
    RULED_LINES,
    /** Word-position clustering on a borderless table (last-resort classical fallback). Least confident. */
    WORD_CLUSTERING
}

/**
 * Recovers real row/column structure from a table crop.
 *
 * Tries three strategies in order, each only used if the one before it
 * fails or produces nothing usable:
 *
 * 1. **SLANet_plus** ([SLANetTableRecognizer]) — PP-Structure's actual
 *    learned table-structure model. Understands merged cells (colspan /
 *    rowspan) and borderless tables, which neither classical strategy
 *    below can. This is a genuinely unverified-on-device integration (see
 *    that class's kdoc for exactly what is and isn't confirmed) — it's
 *    tried first specifically because everything downstream has a real
 *    fallback if it doesn't come through.
 * 2. **Lattice mode** — most printed tables (forms, exam papers, textbook
 *    tables) have visible ruling lines. Morphological erosion+dilation
 *    with a long horizontal/vertical structuring element isolates just
 *    those long straight strokes from the rest of the page content, and
 *    their positions become the real cell grid.
 * 3. **Stream mode** (last resort, when lattice mode can't find at least
 *    2 lines on each axis — a borderless table AND SLANet_plus didn't
 *    come through) — OCR word left/right edges are clustered into
 *    vertical bands across the whole table: a gap that's empty across
 *    nearly every row is treated as a column boundary. Inferring
 *    structure from whitespace, the same idea "stream mode" in tools like
 *    Camelot uses.
 *
 * All three strategies build the grid from the SAME already-OCR'd
 * [OcrWord] list (one whole-table Tesseract pass, done by the caller) —
 * SLANet_plus only supplies cell *boundaries*; which words fall in which
 * cell is the same geometric bucketing all three modes share, so a
 * second, per-cell OCR pass isn't needed.
 */
object TableStructureEngine {

    /** A detected line must span at least this fraction of the crop's opposite dimension to count as real, not noise. */
    private const val MIN_LINE_LENGTH_FRACTION = 0.5

    // Lazily created and reused across every table on every page — loading
    // the ONNX model is expensive, and shouldn't happen per-table.
    private var slanet: SLANetTableRecognizer? = null
    private var slanetInitAttempted = false

    fun recognize(context: Context, tableBitmap: Bitmap, words: List<OcrWord>): TableGrid {
        if (words.isEmpty()) return TableGrid(emptyList(), TableGridSource.WORD_CLUSTERING)

        tryLearnedModel(context, tableBitmap, words)?.let { return it }
        return tryLatticeMode(tableBitmap, words) ?: streamMode(words, tableBitmap.width)
    }

    /** Releases the cached SLANet_plus session — call when a document/session is fully done, not between individual tables. */
    fun releaseModel() {
        slanet?.close()
        slanet = null
        slanetInitAttempted = false
    }

    // ---------- Strategy 1: SLANet_plus (learned model) ----------

    private fun tryLearnedModel(context: Context, tableBitmap: Bitmap, words: List<OcrWord>): TableGrid? {
        if (!slanetInitAttempted) {
            slanetInitAttempted = true
            val instance = SLANetTableRecognizer(context.applicationContext)
            slanet = if (instance.init()) instance else null
        }
        val recognizer = slanet ?: return null

        val slanetCells = recognizer.recognize(tableBitmap) ?: return null
        val rowCount = slanetCells.maxOf { it.rowStart + it.rowSpan }
        val colCount = slanetCells.maxOf { it.colStart + it.colSpan }
        if (rowCount <= 0 || colCount <= 0) return null

        val grid = List(rowCount) { MutableList(colCount) { "" } }
        for (cell in slanetCells) {
            val box = cell.box
            val text = if (box != null) {
                val xs = listOf(box[0], box[2], box[4], box[6])
                val ys = listOf(box[1], box[3], box[5], box[7])
                val left = xs.min(); val right = xs.max()
                val top = ys.min(); val bottom = ys.max()
                words.filter { w ->
                    val cx = (w.left + w.right) / 2f
                    val cy = (w.top + w.bottom) / 2f
                    cx in left..right && cy in top..bottom
                }.sortedBy { it.left }.joinToString(" ") { it.text }
            } else {
                ""
            }
            // Text goes in the cell's top-left slot; other slots a
            // span covers stay blank — see TableGridSource kdoc and
            // DocBlock.Table's kdoc for why a merged cell can't be
            // represented as literally one wide grid cell here.
            if (cell.rowStart in grid.indices && cell.colStart in grid[cell.rowStart].indices) {
                grid[cell.rowStart][cell.colStart] = text
            }
        }
        return TableGrid(grid, TableGridSource.LEARNED_MODEL)
    }

    // ---------- Lattice mode: detect real ruling lines ----------

    private fun tryLatticeMode(bitmap: Bitmap, words: List<OcrWord>): TableGrid? {
        val src = bitmapToMat(bitmap)
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
        val binary = Mat()
        // Inverse threshold: lines and ink become white (255) on a black
        // background, which is what the erode/dilate line extraction below
        // expects (it isolates bright regions).
        Imgproc.adaptiveThreshold(
            gray, binary, 255.0,
            Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 15, 10.0
        )

        val width = bitmap.width
        val height = bitmap.height
        val horizontalKernelLen = (width / 20).coerceAtLeast(15)
        val verticalKernelLen = (height / 20).coerceAtLeast(15)

        val horizontalLines = extractLines(binary, Size(horizontalKernelLen.toDouble(), 1.0))
        val verticalLines = extractLines(binary, Size(1.0, verticalKernelLen.toDouble()))

        val rowBoundaries = lineBandPositions(horizontalLines, axisIsRow = true, minLengthFraction = MIN_LINE_LENGTH_FRACTION, spanDimension = width)
        val colBoundaries = lineBandPositions(verticalLines, axisIsRow = false, minLengthFraction = MIN_LINE_LENGTH_FRACTION, spanDimension = height)

        src.release(); gray.release(); binary.release(); horizontalLines.release(); verticalLines.release()

        // Need at least 2 lines on each axis to define at least one row and one column.
        if (rowBoundaries.size < 2 || colBoundaries.size < 2) return null

        val cells = List(rowBoundaries.size - 1) { r ->
            val cellTop = rowBoundaries[r]
            val cellBottom = rowBoundaries[r + 1]
            List(colBoundaries.size - 1) { c ->
                val cellLeft = colBoundaries[c]
                val cellRight = colBoundaries[c + 1]
                words.filter { w ->
                    val cx = (w.left + w.right) / 2
                    val cy = (w.top + w.bottom) / 2
                    cx in cellLeft..cellRight && cy in cellTop..cellBottom
                }.sortedBy { it.left }.joinToString(" ") { it.text }
            }
        }
        return TableGrid(cells, TableGridSource.RULED_LINES)
    }

    private fun extractLines(binary: Mat, kernelSize: Size): Mat {
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, kernelSize)
        val eroded = Mat()
        val result = Mat()
        Imgproc.erode(binary, eroded, kernel)
        Imgproc.dilate(eroded, result, kernel)
        eroded.release()
        kernel.release()
        return result
    }

    /**
     * Projects the line mask onto the perpendicular axis (a horizontal
     * line's mask is scanned row-by-row; a vertical line's mask, column-
     * by-column), keeps positions whose lit-pixel count implies a real
     * line spanning at least [minLengthFraction] of [spanDimension], and
     * merges consecutive hits into one boundary position each — a real
     * printed rule is several pixels thick, not a single pixel wide.
     */
    private fun lineBandPositions(lineMask: Mat, axisIsRow: Boolean, minLengthFraction: Double, spanDimension: Int): List<Int> {
        val threshold = spanDimension * minLengthFraction
        val count = if (axisIsRow) lineMask.rows() else lineMask.cols()
        val hits = BooleanArray(count)
        for (i in 0 until count) {
            val slice = if (axisIsRow) lineMask.row(i) else lineMask.col(i)
            val nonZero = Core.countNonZero(slice)
            slice.release()
            hits[i] = nonZero >= threshold
        }
        val boundaries = mutableListOf<Int>()
        var runStart = -1
        for (i in 0 until count) {
            if (hits[i] && runStart == -1) runStart = i
            if (!hits[i] && runStart != -1) {
                boundaries.add((runStart + i - 1) / 2)
                runStart = -1
            }
        }
        if (runStart != -1) boundaries.add((runStart + count - 1) / 2)
        return boundaries
    }

    // ---------- Stream mode: infer columns from word-position clustering ----------

    private fun streamMode(words: List<OcrWord>, crossWidth: Int): TableGrid {
        // Group words into rows by vertical overlap — a row is a maximal
        // run of words whose y-intervals overlap, same merging idea
        // ReadingOrder uses for bands, applied here per-word instead of
        // per-region.
        data class RowBucket(var top: Int, var bottom: Int, val items: MutableList<OcrWord>)
        val rowBuckets = mutableListOf<RowBucket>()
        for (w in words.sortedBy { it.top }) {
            val last = rowBuckets.lastOrNull()
            if (last != null && w.top < last.bottom) {
                last.bottom = max(last.bottom, w.bottom)
                last.items.add(w)
            } else {
                rowBuckets.add(RowBucket(w.top, w.bottom, mutableListOf(w)))
            }
        }

        // Cluster column bands from every word's [left, right] interval
        // across the whole table, not per row — a real column boundary is
        // a vertical gap that's empty across (almost) every row, not just
        // one.
        data class Band(var start: Int, var end: Int)
        val minGap = (crossWidth * 0.02).toInt().coerceAtLeast(4)
        val bands = mutableListOf<Band>()
        for ((start, end) in words.map { it.left to it.right }.sortedBy { it.first }) {
            val last = bands.lastOrNull()
            if (last != null && start - last.end < minGap) {
                last.end = max(last.end, end)
            } else {
                bands.add(Band(start, end))
            }
        }

        val cells = rowBuckets.map { row ->
            bands.map { band ->
                row.items.filter { w ->
                    val cx = (w.left + w.right) / 2
                    cx in band.start..band.end
                }.sortedBy { it.left }.joinToString(" ") { it.text }
            }
        }
        return TableGrid(cells, TableGridSource.WORD_CLUSTERING)
    }

    private fun bitmapToMat(bitmap: Bitmap): Mat {
        val mat = Mat()
        val bmp32 = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                    else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        Utils.bitmapToMat(bmp32, mat)
        return mat
    }
}
