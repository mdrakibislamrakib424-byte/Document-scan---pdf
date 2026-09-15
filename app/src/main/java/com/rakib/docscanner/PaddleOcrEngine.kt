package com.rakib.docscanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect

/**
 * English-only OCR path: PP-OCRv5 mobile detection + recognition (ONNX),
 * used instead of Tesseract when the person marks a document as English
 * (see the "This document is in English" toggle) — PP-OCRv5 is generally
 * more accurate than Tesseract on real photographed pages (not flatbed
 * scans), but its bundled recognition model doesn't cover Bengali, which
 * is why Tesseract stays the default for everything else. See
 * [PaddleTextDetector] and [PaddleTextRecognizer] for exactly what's
 * verified vs. assumed in the underlying models.
 *
 * Produces the SAME [OcrPageResult] shape Tesseract's [OcrEngine] does, so
 * every downstream consumer (searchable-PDF export, Digital Re-typeset,
 * table cell mapping) works with either engine unchanged. One real
 * difference in what that result actually contains, stated plainly:
 * PP-OCRv5's detector finds text LINES, not individual words — Tesseract
 * gives real per-word boxes from its own segmentation, but this engine
 * only knows where each *line* is. Word-level boxes here are an estimate
 * — each line's recognized text is split on spaces and given a
 * proportional slice of the line's width by character count. That's
 * accurate enough for what this app uses word boxes for (search/copy
 * position, table cell bucketing), but they are not real per-glyph
 * measurements the way Tesseract's are.
 */
class PaddleOcrEngine(private val context: Context) : TextOcrEngine {

    private val detector = PaddleTextDetector(context)
    private val recognizer = PaddleTextRecognizer(context)

    fun init(): Boolean {
        val detOk = detector.init()
        val recOk = recognizer.init()
        return detOk && recOk
    }

    fun close() {
        detector.close()
        recognizer.close()
    }

    /** [pageSegMode] is ignored — see [TextOcrEngine]'s kdoc for why (this engine's detector always finds its own text regions regardless of any hint). */
    override fun recognize(bitmap: Bitmap, pageSegMode: Int): OcrPageResult = recognize(bitmap)

    fun recognize(bitmap: Bitmap): OcrPageResult {
        val lineBoxes = orderReadingFlow(detector.detect(bitmap))
        if (lineBoxes.isEmpty()) return OcrPageResult(fullText = "", words = emptyList(), paragraphs = emptyList())

        // (text, box) pairs, built together so a skipped line (blank OCR
        // result, or a crop that failed) can never leave `lines` and
        // `lineBoxes` misaligned with each other — see kdoc on why that
        // matters for groupIntoParagraphs below.
        val recognizedLines = mutableListOf<Pair<String, Rect>>()
        val allWords = mutableListOf<OcrWord>()
        val lineWordsList = mutableListOf<List<OcrWord>>()

        for (box in lineBoxes) {
            val crop = safeCrop(bitmap, box) ?: continue
            val text = try { recognizer.recognize(crop) } finally { crop.recycle() }
            if (text.isBlank()) continue

            recognizedLines.add(text to box)
            val wordsInLine = estimateWordBoxes(text, box)
            allWords.addAll(wordsInLine)
            lineWordsList.add(wordsInLine)
        }

        val paragraphs = groupIntoParagraphs(recognizedLines)
        return OcrPageResult(
            fullText = recognizedLines.joinToString("\n") { it.first },
            words = allWords,
            paragraphs = paragraphs,
            lines = recognizedLines.map { it.first },
            lineWords = lineWordsList
        )
    }

    private fun safeCrop(bitmap: Bitmap, box: Rect): Bitmap? {
        val left = box.left.coerceIn(0, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val right = box.right.coerceIn(left + 1, bitmap.width)
        val bottom = box.bottom.coerceIn(top + 1, bitmap.height)
        return try {
            Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        } catch (e: Exception) {
            null
        }
    }

    /** Splits a recognized line's text into words, giving each a horizontal slice of the line's box proportional to its character count (see class kdoc — an estimate, not a real per-word measurement). */
    private fun estimateWordBoxes(lineText: String, box: Rect): List<OcrWord> {
        val words = lineText.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()

        val totalChars = words.sumOf { it.length }.coerceAtLeast(1)
        val lineWidth = (box.right - box.left).toFloat()
        var cursor = box.left.toFloat()
        val result = mutableListOf<OcrWord>()
        for (word in words) {
            val share = word.length.toFloat() / totalChars
            val wordWidth = lineWidth * share
            result.add(
                OcrWord(
                    text = word,
                    left = cursor.toInt(),
                    top = box.top,
                    right = (cursor + wordWidth).toInt(),
                    bottom = box.bottom,
                    confidence = 0f // PP-OCRv5's per-character confidence isn't surfaced through this simple decode; not used downstream for filtering today, so left as an honest "unknown" rather than a fabricated number.
                )
            )
            cursor += wordWidth
        }
        return result
    }

    /**
     * Groups consecutive lines into paragraphs by vertical gap — lines
     * whose gap to the previous line is small relative to their own
     * height are treated as the same paragraph, a large gap starts a new
     * one. Deliberately simple (this engine only gets whole-page or
     * whole-region calls today, never a case needing [LayoutEngine]-grade
     * layout awareness); the layout-aware pipeline in
     * [DocumentStructureEngine] does its own, separate paragraph handling
     * per detected region and doesn't call this.
     */
    private fun groupIntoParagraphs(recognizedLines: List<Pair<String, Rect>>): List<String> {
        if (recognizedLines.isEmpty()) return emptyList()
        val paragraphs = mutableListOf<String>()
        val current = StringBuilder(recognizedLines[0].first)

        for (i in 1 until recognizedLines.size) {
            val (text, box) = recognizedLines[i]
            val prevBox = recognizedLines[i - 1].second
            val gap = box.top - prevBox.bottom
            val prevHeight = (prevBox.bottom - prevBox.top).coerceAtLeast(1)

            if (gap > prevHeight * 0.6) {
                paragraphs.add(current.toString())
                current.clear()
                current.append(text)
            } else {
                current.append(" ").append(text)
            }
        }
        paragraphs.add(current.toString())
        return paragraphs
    }

    /** Simple top-to-bottom, left-to-right sort — line boxes from a detector don't need [ReadingOrder]'s column-aware XY-cut, which is built for whole-page layout regions, not individual text lines. */
    private fun orderReadingFlow(boxes: List<Rect>): List<Rect> {
        return boxes.sortedWith(compareBy({ it.top / 20 }, { it.left }))
    }
}
