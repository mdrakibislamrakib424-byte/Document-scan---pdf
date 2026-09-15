package com.rakib.docscanner

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.max

/**
 * Wraps SLANet_plus (~6.9MB ONNX) — PP-Structure's actual learned
 * table-structure model, doing what [TableStructureEngine]'s classical
 * lattice/stream approach can't: understanding merged cells (colspan /
 * rowspan) and reading borderless tables without depending on whitespace
 * gaps lining up cleanly.
 *
 * ============================================================
 * WHAT'S VERIFIED vs. WHAT'S THIS CLASS'S OWN RECONSTRUCTION
 * ============================================================
 * Verified directly from PaddleOCR's own published training config and
 * source (not guessed), across several independently-consistent sources:
 *  - Preprocessing pipeline and exact order: DecodeImage(BGR) ->
 *    ResizeTableImage(proportional, longer side = 488) ->
 *    NormalizeImage(scale 1/255, mean/std applied in that same BGR
 *    channel order — NOT re-ordered to RGB) -> PaddingTableImage(pad to
 *    488x488) -> ToCHWImage.
 *  - Output contract: structure_probs [1,T,V] (per-step token logits) and
 *    loc_preds [1,T,8] (an xyxyxyxy quad per step); box_format confirmed
 *    as 'xyxyxyxy' in the training config.
 *  - The postprocess class (TableLabelDecode) reads the vocabulary file
 *    line-by-line, and — because SLANet_plus's config sets
 *    merge_no_span_structure: true — removes a plain "<td>" entry if
 *    present and ensures "<td></td>" is present (the no-span case is one
 *    token, not two). Cells that DO have colspan/rowspan instead go
 *    through the multi-token sequence "<td" + attribute token(s) + ">"
 *    ... "</td>". A cell's position in loc_preds is read at whichever
 *    token opens it — "<td>", "<td", or "<td></td>" (this `td_token`
 *    list is from the actual source).
 *  - The base class (AttnLabelDecode/BaseRecLabelDecode) uses "sos"/"eos"
 *    as its begin/end tokens, prepended/appended around the dict's own
 *    entries.
 *
 * NOT independently verified — this class's own reconstruction, because
 * the exact grid-assembly code inside TableLabelDecode.decode() wasn't
 * available to check line-by-line:
 *  - [buildGrid]'s occupancy-matrix walk (placing each cell at the next
 *    free slot in the current row, marking spanned slots occupied) is a
 *    standard, well-established technique for turning an HTML-style
 *    token stream into a grid — not something invented for this app —
 *    but it's this class's own implementation of that standard technique,
 *    not a verified port of PaddleOCR's exact code.
 *  - This specific ONNX export's exact input/output tensor **names**
 *    weren't confirmed, so they're discovered at runtime by tensor
 *    rank/shape instead of hardcoded — see [runInference].
 *  - Whether `loc_preds` values are normalized to [0,1] or already in
 *    absolute 0-488 pixel space wasn't confirmed either; [denormalizeBox]
 *    handles both.
 *
 * The vocabulary itself is NOT hardcoded in this file — it's downloaded
 * at build time from PaddleOCR's own repo (see the CI workflow) into
 * assets, and read at [init] time, specifically so a guess-the-token-list
 * mistake can't silently corrupt every table.
 *
 * Given the above, this is a genuinely unverified-on-device integration —
 * more so than anything else in this app. [TableStructureEngine] only
 * uses this when it initializes AND produces a non-empty grid; any
 * failure at any step falls back to the classical lattice/stream
 * approach, which was already working.
 */
class SLANetTableRecognizer(private val context: Context) {

    data class Cell(
        val rowStart: Int,
        val colStart: Int,
        val rowSpan: Int,
        val colSpan: Int,
        val box: FloatArray? // 8 values: x1,y1,x2,y2,x3,y3,x4,y4 in the ORIGINAL bitmap's pixel space, or null if this cell had no box (shouldn't normally happen for a real td token)
    )

    companion object {
        private const val INPUT_SIZE = 488
        private const val MODEL_ASSET_PATH = "models/slanet_plus.onnx"
        private const val DICT_ASSET_PATH = "models/table_structure_dict_ch.txt"
        private const val BEG_TOKEN = "sos"
        private const val END_TOKEN = "eos"

        // From the actual TableLabelDecode source — these three token
        // spellings are each where a cell's loc_preds box lives.
        private val TD_OPEN_TOKENS = setOf("<td>", "<td", "<td></td>")
        private const val TD_CLOSE = "</td>"
        private const val TR_OPEN = "<tr>"
    }

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private var vocabulary: List<String> = emptyList()

    fun init(): Boolean {
        return try {
            val dictLines = context.assets.open(DICT_ASSET_PATH).bufferedReader(Charsets.UTF_8)
                .readLines()
                .map { it.trimEnd('\n', '\r') }
                .filter { it.isNotEmpty() }

            val merged = dictLines.toMutableList()
            if ("<td>" in merged) merged.remove("<td>")
            if ("<td></td>" !in merged) merged.add("<td></td>")

            vocabulary = listOf(BEG_TOKEN) + merged + listOf(END_TOKEN)

            val modelBytes = context.assets.open(MODEL_ASSET_PATH).use { it.readBytes() }
            val ortEnv = OrtEnvironment.getEnvironment()
            val ortSession = ortEnv.createSession(modelBytes, OrtSession.SessionOptions())
            env = ortEnv
            session = ortSession
            true
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        try { session?.close() } catch (_: Exception) {}
        session = null
    }

    /** Returns the recognized grid's cells, or null on any failure (caller falls back to the classical approach). */
    fun recognize(tableBitmap: Bitmap): List<Cell>? {
        val activeSession = session ?: return null
        if (vocabulary.isEmpty()) return null

        return try {
            val prep = preprocess(tableBitmap)
            val inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(prep.chwData), longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()))
            val outputs = runInference(activeSession, inputTensor) ?: return null

            val (structureProbs, locPreds) = outputs
            val (tokens, boxes) = decodeSequence(structureProbs, locPreds, prep)
            buildGrid(tokens, boxes)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Preprocessing ----------

    private class PreprocessResult(
        val chwData: FloatArray,
        val scale: Double,       // INPUT_SIZE / max(origW, origH) — proportional resize factor actually used
        val origW: Int,
        val origH: Int
    )

    /**
     * BGR, proportional resize (longer side -> 488), ImageNet-normalize in
     * that same BGR channel order (verified — see class kdoc), then pad
     * to 488x488. Padding is applied AFTER normalization, matching the
     * verified transform order, so the pad fill value is 0.0f in
     * normalized space (a neutral value for the network, not an attempt
     * to reverse-derive an equivalent source pixel color).
     */
    private fun preprocess(bitmap: Bitmap): PreprocessResult {
        val mat = Mat()
        val bmp32 = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                    else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        Utils.bitmapToMat(bmp32, mat)
        val bgr = Mat()
        Imgproc.cvtColor(mat, bgr, Imgproc.COLOR_RGBA2BGR)

        val origW = bitmap.width
        val origH = bitmap.height
        val scale = INPUT_SIZE.toDouble() / max(origW, origH)
        val scaledW = (origW * scale).toInt().coerceIn(1, INPUT_SIZE)
        val scaledH = (origH * scale).toInt().coerceIn(1, INPUT_SIZE)

        val resized = Mat()
        Imgproc.resize(bgr, resized, Size(scaledW.toDouble(), scaledH.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)

        val floatMat = Mat()
        resized.convertTo(floatMat, CvType.CV_32FC3, 1.0 / 255.0)
        // mean/std applied positionally to whatever channel order the Mat
        // is already in (BGR here) — verified this pipeline never
        // reorders to RGB before normalizing, so index 0 really is B.
        Core.subtract(floatMat, Scalar(0.485, 0.456, 0.406), floatMat)
        Core.divide(floatMat, Scalar(0.229, 0.224, 0.225), floatMat)

        val chwData = FloatArray(3 * INPUT_SIZE * INPUT_SIZE) // zero-initialized -> the pad fill value
        val plane = INPUT_SIZE * INPUT_SIZE
        val rowBuf = FloatArray(scaledW * 3)
        for (y in 0 until scaledH) {
            floatMat.get(y, 0, rowBuf)
            for (x in 0 until scaledW) {
                val base = x * 3
                // Top-left aligned padding (resized content at (0,0),
                // padding added to the right/bottom) — the conventional
                // default for this kind of op when a specific alignment
                // isn't stated; not independently confirmed for this
                // exact model.
                chwData[0 * plane + y * INPUT_SIZE + x] = rowBuf[base]
                chwData[1 * plane + y * INPUT_SIZE + x] = rowBuf[base + 1]
                chwData[2 * plane + y * INPUT_SIZE + x] = rowBuf[base + 2]
            }
        }

        return PreprocessResult(chwData, scale, origW, origH)
    }

    // ---------- Inference ----------

    /**
     * Input/output tensor names for this specific ONNX export weren't
     * confirmed, so this discovers them structurally instead of guessing:
     * the single input tensor gets the image data regardless of its name;
     * outputs are told apart by rank — a rank-3 tensor with last dim 8 is
     * the box stream, a rank-3 tensor with a larger last dim is the token
     * logits.
     */
    private fun runInference(session: OrtSession, imageTensor: OnnxTensor): Pair<Array<Array<FloatArray>>, Array<Array<FloatArray>>>? {
        return try {
            val inputName = session.inputNames.iterator().next()
            val result = session.run(mapOf(inputName to imageTensor))
            try {
                var structureProbs: Array<Array<FloatArray>>? = null
                var locPreds: Array<Array<FloatArray>>? = null
                for (entry in result) {
                    val tensor = entry.value as? OnnxTensor ?: continue
                    val shape = tensor.info.shape
                    if (shape.size != 3) continue
                    @Suppress("UNCHECKED_CAST")
                    val value = tensor.value as? Array<Array<FloatArray>> ?: continue
                    if (shape[2] == 8L) locPreds = value else structureProbs = value
                }
                if (structureProbs == null || locPreds == null) null else Pair(structureProbs, locPreds)
            } finally {
                result.close()
            }
        } catch (e: Exception) {
            null
        } finally {
            imageTensor.close()
        }
    }

    // ---------- Sequence decode ----------

    private fun decodeSequence(
        structureProbs: Array<Array<FloatArray>>,
        locPreds: Array<Array<FloatArray>>,
        prep: PreprocessResult
    ): Pair<List<String>, List<FloatArray?>> {
        val steps = structureProbs[0]
        val locSteps = locPreds[0]
        val endIndex = vocabulary.indexOf(END_TOKEN)

        val tokens = mutableListOf<String>()
        val boxes = mutableListOf<FloatArray?>()

        for (t in steps.indices) {
            val logits = steps[t]
            var bestIdx = 0
            var bestVal = Float.NEGATIVE_INFINITY
            for (i in logits.indices) if (logits[i] > bestVal) { bestVal = logits[i]; bestIdx = i }

            if (bestIdx == endIndex) break
            if (bestIdx !in vocabulary.indices) continue
            val token = vocabulary[bestIdx]
            if (token == BEG_TOKEN) continue

            tokens.add(token)
            boxes.add(if (token in TD_OPEN_TOKENS && t < locSteps.size) denormalizeBox(locSteps[t], prep) else null)
        }
        return Pair(tokens, boxes)
    }

    /**
     * Maps a raw loc_preds row back to the ORIGINAL bitmap's pixel space:
     * undo the proportional-resize scale (no pad-offset to undo, since
     * padding was top-left aligned — see [preprocess]). Handles both a
     * normalized [0,1] and an already-pixel-scale [0,488] output, since
     * which one this export uses wasn't confirmed — values comfortably
     * below 2.0 are treated as normalized.
     */
    private fun denormalizeBox(raw: FloatArray, prep: PreprocessResult): FloatArray {
        val looksNormalized = raw.all { it in -0.05f..2.0f }
        val toInputPx: (Float) -> Float = if (looksNormalized) { v -> v * INPUT_SIZE } else { v -> v }
        return FloatArray(raw.size) { i ->
            val inputPx = toInputPx(raw[i])
            val origPx = inputPx / prep.scale.toFloat()
            val isX = i % 2 == 0
            val maxVal = if (isX) prep.origW.toFloat() else prep.origH.toFloat()
            origPx.coerceIn(0f, maxVal)
        }
    }

    // ---------- Grid assembly ----------

    /**
     * Standard HTML-token-stream-to-grid reconstruction (this app's own
     * implementation of a well-established technique, not a verified port
     * of PaddleOCR's exact code — see class kdoc): walk the tokens,
     * advance to a new row on `<tr>`, and for each cell-open token place
     * it at the next column slot in the current row that isn't already
     * occupied by an earlier row/colspan, marking every slot it spans
     * (rowSpan x colSpan) as occupied so later cells skip over them.
     */
    private fun buildGrid(tokens: List<String>, boxes: List<FloatArray?>): List<Cell>? {
        if (tokens.isEmpty()) return null

        val occupied = HashSet<Pair<Int, Int>>() // (row, col) already claimed by a spanning cell
        val cells = mutableListOf<Cell>()
        var row = 0
        var seenAnyRow = false
        var col = 0
        var i = 0

        while (i < tokens.size) {
            val token = tokens[i]
            when {
                token == TR_OPEN -> {
                    // First <tr> establishes row 0; only later ones advance
                    // it. (Not "row++ starting from -1" — that let a
                    // malformed sequence with a cell token before the very
                    // first <tr> get tracked internally at row -1 while
                    // being reported as row 0, letting a genuinely-row-0
                    // cell later overlap it undetected. Tracking a real,
                    // never-negative row throughout removes that gap.)
                    if (seenAnyRow) row++ else seenAnyRow = true
                    col = 0
                    i++
                }
                token in TD_OPEN_TOKENS -> {
                    var colSpan = 1
                    var rowSpan = 1
                    var j = i

                    if (token == "<td") {
                        // Multi-token cell: consume attribute tokens until ">".
                        j++
                        while (j < tokens.size && tokens[j] != ">") {
                            val attr = tokens[j]
                            Regex("""colspan="(\d+)"""").find(attr)?.let { colSpan = it.groupValues[1].toIntOrNull() ?: 1 }
                            Regex("""rowspan="(\d+)"""").find(attr)?.let { rowSpan = it.groupValues[1].toIntOrNull() ?: 1 }
                            j++
                        }
                        // j now at ">" (or end of sequence if malformed — treat as a normal 1x1 cell either way)
                    }

                    while (occupied.contains(row to col)) col++

                    val box = boxes.getOrNull(i)
                    cells.add(Cell(row, col, rowSpan, colSpan, box))
                    for (r in row until row + rowSpan) for (c in col until col + colSpan) occupied.add(r to c)
                    col += colSpan

                    // Skip forward past this cell's attribute tokens and
                    // its closing </td> (if present) so the outer loop
                    // doesn't re-process them as their own tokens.
                    i = j + 1
                    if (i < tokens.size && tokens[i] == TD_CLOSE) i++
                }
                else -> i++ // <table>, <thead>, </tr>, etc. — structural noise for grid purposes
            }
        }

        return cells.ifEmpty { null }
    }
}
