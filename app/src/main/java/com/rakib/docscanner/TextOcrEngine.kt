package com.rakib.docscanner

import android.graphics.Bitmap

/**
 * Common shape for [OcrEngine] (Tesseract, ben+eng, the default) and
 * [PaddleOcrEngine] (PP-OCRv5, English-only, opt-in — see its kdoc for
 * why both exist rather than one replacing the other) so the rest of the
 * app — [DocumentStructureEngine], the searchable-PDF export, Digital
 * Re-typeset — calls whichever engine the person chose without caring
 * which one it actually is.
 *
 * [pageSegMode] is Tesseract's "page segmentation mode" hint (see
 * [OcrEngine.recognize]'s kdoc for what it's for and why it's typed
 * `Int`, not an enum). [PaddleOcrEngine] has no equivalent concept — its
 * detector always finds text regions itself regardless of any hint — so
 * it simply ignores the parameter. Keeping it in the shared interface
 * rather than splitting it out was the simpler choice: an ignored int is
 * a smaller cost than two different call-site shapes throughout
 * [DocumentStructureEngine].
 */
interface TextOcrEngine {
    fun recognize(bitmap: Bitmap, pageSegMode: Int): OcrPageResult
}
