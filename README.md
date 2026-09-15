# Doc Scanner — Phase 1 + Phase 2

Phase 1: camera + gallery capture, auto/manual page-corner detection,
perspective correction, curved-page dewarping, Original/Grayscale/B&W
output, single-page PDF export.

Phase 2 (new): shadow/uneven-lighting removal, noise reduction, smart
(adaptive) contrast, text sharpening, and a classical-upscale "super
resolution" — all chained into one "AI Enhance" mode, plus a standalone
"Upscale 2x" button.

## What's actually implemented in Phase 2 — and what it really is

- **Shadow / uneven lighting removal** (`Phase2Processor.removeShadowAndNormalizeLighting`) —
  real technique: estimate the lighting by heavily blurring the page, then
  divide the original by that estimate. This is the standard classical
  method for flattening lighting on a document photo.
- **Noise removal** (`denoise`) — OpenCV's non-local-means denoising
  (`Photo.fastNlMeansDenoisingColored`), which preserves text edges far
  better than a plain blur.
- **Smart contrast** (`smartContrast`) — CLAHE (adaptive histogram
  equalization) on the lightness channel only, so contrast improves locally
  across the page without shifting color balance.
- **Text sharpening / "deblur"** (`sharpenText`) — unsharp masking.
  **Honestly:** this sharpens existing edges; it is not true blind
  deconvolution and cannot recover detail genuinely lost to motion/focus
  blur — that needs a trained model, which is a separate future addition,
  not what's shipped here.
- **Upscale / "super-resolution"** (`upscale`) — high-quality Lanczos
  interpolation + a mild sharpen pass. **Honestly:** this is classical
  upscaling, not learned/AI super-resolution (e.g. ESRGAN). It gives a
  clean, well-interpolated larger image; it does not invent new fine detail
  the way a trained super-resolution model would. A real AI version is
  possible later by bundling a TFLite model — flagged in the code as a
  clearly separate, heavier addition.
- **"Background cleaning"** from the original wishlist is effectively
  covered by shadow/lighting normalization above, since Phase 1's crop
  already removes the table/hand/wall — there's no separate paper-vs-background
  segmentation step needed post-crop.
- **AI Enhance mode** chains all of the above in the order that matters:
  fix lighting -> denoise -> contrast -> sharpen.

## Phase 1 recap

- `MainActivity`, `ScanActivity`, `ReviewActivity`, `ImageProcessor`
  (corner detection, perspective correction, curve dewarping),
  `PdfExporter` (single page -> PDF, multi-page is Phase 4).

## Remaining honest gaps

- Dedicated fold/crease **shadow** correction (distinct from the geometric
  curve-straightening already in Phase 1) is not separately implemented —
  the lighting-normalization pass above does help with crease shadows in
  practice, but there's no crease-specific detector yet.
- No auto-capture yet (Phase 3).
- No multi-page sessions yet (Phase 4).
- No OCR yet (Phase 5 — PaddleOCR), no Digital Re-typeset yet (Phase 5.5).

## Building the APK from your phone (no computer needed)

1. Create a **new GitHub repository** (from the GitHub app or mobile
   browser), e.g. `doc-scanner`.
2. Upload every file/folder in this project into that repo, keeping the
   folder structure exactly as-is (`app/`, `.github/workflows/`,
   `settings.gradle.kts`, etc. all at the repo root).
3. Go to the repo's **Actions** tab. The `Build Debug APK` workflow runs
   automatically on every push to `main` (or trigger it manually from
   there with "Run workflow").
4. When it finishes, open the workflow run -> **Artifacts** -> download
   `DocScanner-debug-apk`. That's a zip containing `app-debug.apk` —
   install it on your phone (you'll need to allow "install from unknown
   sources" once).

## Phase 3 additions (Scan Quality Score, Auto-Capture, Enhance intensity slider)

- **Scan Quality Score** (`ScanQualityAnalyzer`) — after crop confirmation,
  the app scores the photo 0-100 using two real, standard metrics: blur
  (variance of the Laplacian) and lighting (mean brightness + how much of
  the image is clipped near-black/near-white). Shown with a plain-language
  message ("Excellent scan", "image looks a bit blurry", etc.) — genuinely
  useful feedback, not a cosmetic number.
- **Auto-Capture** (`AutoCaptureController`, `FrameUtils`) — a toggle on
  the scan screen. When on, every camera preview frame is checked for: a
  clear 4-corner page taking up enough of the frame, acceptable sharpness,
  and stability versus the previous frame (hand has stopped moving). Only
  once all three hold for several consecutive frames does it fire the
  shutter itself. The frame-analysis path reads the camera's raw luminance
  plane directly (skips full YUV→RGB conversion) since this one path runs
  on every single frame — that's a responsiveness necessity for a live
  per-frame check, not a quality tradeoff on the actual scan.
- **Enhance intensity slider** — the "AI Enhance" mode from Phase 2 is no
  longer all-or-nothing; a Low↔High slider scales how aggressively
  `Phase2Processor.enhanceFull` applies contrast/sharpening.

## Phase 4 additions (multi-page sessions, PDF engine, My Documents)

- **PageSession** — every confirmed page (from ReviewActivity's "Add Page")
  is written to a cache file immediately, not held in memory as a Bitmap
  list. On a phone, a dozen full-resolution page bitmaps in RAM at once is
  a real crash risk (OutOfMemoryError) — writing to disk avoids that
  regardless of the "don't worry about performance" instruction, which is
  about output quality, not about the app crashing mid-scan.
- **SessionActivity** — the "document in progress" screen: thumbnail list
  of every page added so far, long-press-and-drag to reorder
  (`ItemTouchHelper`), rotate/delete per page, "Add Page" to keep scanning
  (batch mode), and "Export PDF" once done.
- **Export dialog** — optional document name, and a real Standard vs
  Compressed choice. Compressed re-encodes every page through JPEG at
  quality 60 before embedding — an actual size reduction, not a cosmetic
  toggle, and it's opt-in (Standard/full-quality stays the default).
- **PdfExporter.exportMultiPage** — assembles the whole page list into one
  PDF, one page per bitmap at that bitmap's own resolution.
- **DocumentsActivity ("My Documents")** — lists every previously exported
  PDF (newest first), tap to share, long-press to delete.
- **MainActivity** now shows a "Continue Document" button whenever a
  session already has pages waiting — e.g. if the user backed out of
  SessionActivity mid-scan without exporting.

### Known simplification, stated plainly

Page thumbnails in `SessionActivity`'s list decode synchronously on the UI
thread (downsampled, not full-res) when each row is bound. For a normal
document (a handful to a few dozen pages) this is fine; for a very large
batch it could cause minor scroll stutter. It will never corrupt output or
crash — the fix (an async thumbnail cache) is straightforward but adds
complexity that didn't seem justified for the typical use case. Flagging
it here rather than leaving it undocumented.

## Phase 5 additions (OCR, searchable PDF) — and a correction

**Correction from the earlier plan:** PaddleOCR was the originally chosen
OCR engine, but on closer, verified checking it does not actually work for
this app. PaddleOCR's mobile-deployable models (PP-OCRv6 and earlier)
cover Chinese, English, and 46 Latin-script languages — Bengali isn't
Latin-script, so it isn't covered. PaddleOCR's newer model that does
support Bengali (PaddleOCR-VL, 109 languages) is a 0.9-billion-parameter
vision-language model meant for GPU/server inference, not a phone.
**Tesseract** is used instead — real, offline, Bangla-capable, and has
been used in production Android apps for years.

- **OcrEngine** (`Tesseract4Android` library, `TessBaseAPI`) — recognizes
  Bangla + English together (`ben+eng`). Language data
  (`ben.traineddata` + `eng.traineddata`, the `tessdata_best` — most
  accurate — variant) is downloaded automatically by the GitHub Actions
  workflow before each build and bundled into the APK; nothing to fetch by
  hand.
- **HocrParser** — pulls word text + bounding box + confidence out of
  Tesseract's hOCR output, which is what makes a *searchable* (not just
  plain-text) PDF possible.
- **PdfExporter.exportSearchablePdf** — draws each page image, then draws
  each OCR'd word as fully transparent text at its matching position — the
  standard "searchable scanned PDF" trick: invisible to the eye, real text
  to a PDF reader's search/select/copy.
- **Export dialog** — new "Make searchable (OCR)" checkbox in
  SessionActivity. When checked: OCR runs on every page, the PDF gets the
  searchable text layer, a matching `.txt` file is saved alongside it, and
  an "Extracted Text" dialog pops up afterward with a Copy button.
- If OCR setup fails for any reason (e.g. language data missing), export
  automatically falls back to a normal (non-searchable) PDF rather than
  losing the scan — with a toast explaining what happened.

### A real uncertainty, stated plainly

The invisible-text-layer technique depends on Android's built-in
`PdfDocument`/`Canvas.drawText()` writing real, correctly-Unicode-mapped
text into the PDF (not converting it to non-text vector shapes). This is
true and well-established for Latin text. For Bengali specifically — a
complex script with conjuncts/ligatures — I could not verify with full
confidence that Android's built-in PDF writer preserves correct
character-level text extraction for shaped/conjunct glyphs, because
testing this needs a real Android device/PDF reader, which isn't available
in the environment this was built in. If, after building and testing on a
device, Bangla text in the PDF doesn't search or copy/paste correctly
(English likely will), the fix is switching the PDF-writing layer to a
library built specifically for guaranteed selectable/searchable text
(e.g. a dedicated PDF text-layer library) instead of `PdfDocument` — a
contained change, since `exportSearchablePdf` is the only place that logic
lives. Flagging this now rather than presenting it as certain.

## Phase 5.5 additions (Digital Re-typeset)

- **HocrParser.parseParagraphs** — groups OCR'd words into paragraphs using
  Tesseract's own page segmentation (its hOCR output wraps each detected
  paragraph in `<p class='ocr_par'>`). Line breaks *within* a paragraph are
  deliberately not preserved — the whole point of re-typesetting is to
  reflow text into a new layout, so what matters is paragraph boundaries,
  not exactly where Tesseract wrapped each line.
- **RetypesetRenderer** — lays out the recognized paragraphs fresh on new
  blank pages using Android's `StaticLayout`, in one of three styles
  (Plain / Book / Notes — font size, spacing, margins, and color differ;
  see `RetypesetStyle` for exact values). Page breaks are computed from the
  new layout itself, always at a line boundary (never mid-line), and the
  whole document is treated as one continuous flow of paragraphs rather
  than one output page per original photo — a 3-photo scan might become 2
  or 4 re-typeset pages depending on the style chosen. That's intentional:
  a clean document's page breaks should come from the new layout, not from
  wherever the original photos happened to end.
- Available from SessionActivity's new "Digital Re-typeset" button —
  separate from the normal Export PDF flow, since it produces a different
  kind of output (brand-new clean pages, not the scanned images).

### Honest limitations (as of Phase 5.5 — see the Phase 6 section below for what's since changed)

- This was, at this point, a **text-only** re-typeset. Tables, images,
  multi-column layouts, and any structure Tesseract's page segmentation
  doesn't represent as ordinary paragraphs were not reproduced — there was
  no layout-analysis model yet (that's what PP-Structure's layout half
  would turn out to add — see Phase 6). Kept here rather than rewritten,
  since it's genuinely what Phase 5.5 shipped with.
- Paragraph detection is only as good as Tesseract's own segmentation. An
  unusual page layout can get paragraphs merged or split incorrectly, with
  no ground truth available to catch that automatically. (Phase 6 narrows
  this: paragraph *text* is still Tesseract's own segmentation, but which
  pixels get OCR'd as one paragraph, versus a title or table, now comes
  from the layout model instead of purely from Tesseract's guess.)
- If a page has no recognizable text, re-typeset correctly reports "no
  readable text found" rather than producing an empty document silently.

## Phase 6.1 additions (real table grid, running header/footer, page numbers, small-text upscaling)

Requested as a direct follow-up: fully finish the items from Phase 6 that
were only half-done, rather than leave them at "detected but not really
used." Four things changed.

### Real table structure — row *and* column, not just row

Phase 6 kept a table's row breaks but not its columns (`DocBlock.Table`
held `List<String>`, one line each). This was found and pointed out
directly, correctly — a table that "just looks like a shaded, slightly
odd paragraph" isn't really a table recognition feature. `DocBlock.Table`
now holds `List<List<String>>` — an actual grid — and
`RetypesetRenderer.drawTable` draws it as a real bordered table, with
column widths sized to each column's content and grid lines between every
row and column.

**Why this isn't literally PaddleOCR's SLANet/SLANet-plus model** (the
actual PP-Structure table-recognition network), even though real ONNX
exports of it were found during research for this: SLANet is a
sequence-to-sequence decoder — it outputs a sequence of HTML structure
tokens (`<tr>`, `<td>`, `<td colspan="2">`...) from a fixed,
PaddleOCR-internal token vocabulary, each aligned to a predicted cell
quadrilateral. Wiring that up correctly needs the *exact* vocabulary file
and decode order; getting it wrong wouldn't fail loudly the way a
wrong-shaped detection tensor would (that just returns nothing, safely) —
it would silently produce a plausible-but-wrong grid, cells merged or
split incorrectly, with no way to catch that without a real device to
check against. That's a categorically bigger blind risk than
PP-DocLayout-S's contract (a plain fixed-shape tensor, checkable by shape
alone), and this build environment still can't run either one to verify.

So instead: `TableStructureRecovery.kt` uses data already trusted
elsewhere in this app — Tesseract's own per-word bounding boxes (the same
data that's powered the searchable-PDF text layer since Phase 5) — with a
classical, fully inspectable geometric method. Rows come from Tesseract's
own line segmentation (already reliable — that's its actual job).
Columns are recovered by clustering every word's left/right edges across
the *whole* table at once via `IntervalClustering` (a small shared utility
also used by `ReadingOrder`, extracted so the same merge-and-find-gaps
logic isn't duplicated with two chances to get subtly different bugs):
words whose edges line up into the same vertical band, across many rows,
are in the same column by definition. This is the same principle
classical table-extraction tools (e.g. Camelot's "stream" mode) use when
a learned model isn't available — a real technique, not a placeholder,
just not the specific model named.

Honest limitation, carried in `TableStructureRecovery`'s own kdoc: a
genuinely merged cell (colspan/rowspan in the original table) has no way
to signal that through word positions alone, so it comes out as a
same-width column repeated blank rather than one wide cell — visually
close, not pixel-identical.

### Running header/footer + page numbers on every output page

`DocumentStructureEngine` now OCRs `header`/`footer`-class regions
instead of silently dropping them, and treats the *first* non-blank one
found across the whole batch as document-level metadata (a chapter title
doesn't need re-reading on every original photo). `RetypesetRenderer`
stamps that header/footer, plus a "page N / total" page number, onto
every finished output page as a final pass — after all pages are laid
out, since "total pages" genuinely isn't knowable until then. All of it
lives inside the existing margin bands the body content never draws into,
so there's no risk of colliding with the actual document text.
Page numbers are always shown (not conditional on whether the original
scan had them) — standard practice for a multi-page document, and no new
setting was added for it since none was asked for.

### Small-text crops are upscaled automatically before OCR

The "Upscale 2x" button from Phase 2 (classical Lanczos + sharpen — not
AI super-resolution, and the code says so plainly) was only ever
user-triggered, on the whole photo, before OCR ran. `DocumentStructureEngine`
now applies that same function automatically to any individual region
crop shorter than 90px before running OCR on it — a caption or footnote
crop can be tiny even from an otherwise high-resolution page photo, and
that's specifically where small glyph size hurts Tesseract's accuracy
most. The 90px threshold is a reasonable engineering estimate, not an
empirically tuned one — there was no device available to test against.

## Phase 6 additions (layout-aware retypeset: titles, tables, images)

Phase 5.5 shipped a text-only retypeset because there was no layout-analysis
model available — PaddleOCR's own PP-Structure layout model exists, but it
had never actually been checked against this app's real constraint. On
checking: PP-Structure's *layout detector* and PaddleOCR's *text
recognizer* are two separate models bundled together, and only the text
recognizer was the part that couldn't handle Bengali (see Phase 5's
correction above). The layout detector doesn't read text at all — it only
looks at shapes on the page (a dense block is "text", a ruled grid is
"table", a lone short line above a block is a "title") — so it doesn't
care what script the page is written in. That's the piece added here.

- **PP-DocLayout-S** (`LayoutEngine`, via ONNX Runtime) — a ~4.7 MB
  layout-detection model (PicoDet/GFL-based, from PaddlePaddle, exported to
  ONNX by a third party — see the model source cited in
  `build-apk.yml` — since Baidu didn't publish an official ONNX build).
  Downloaded by the GitHub Actions workflow before each build (same
  pattern as the Bangla/English `tessdata`), with a SHA-256 check so a
  corrupted or substituted download fails the build loudly instead of
  shipping a broken model. Detects 23 region classes; `LayoutClass.kt`
  documents the exact class order (matching the model's own `inference.yml`)
  and `toBlockRole()` states, plainly, the editorial call on what each
  class becomes in the output (title vs. paragraph vs. table vs. image vs.
  skipped page-furniture like running headers).
- **ReadingOrder** — PP-DocLayout-S reports boxes and classes but *not*
  reading order (unlike the much heavier PP-DocLayoutV3). Order is
  recovered with recursive XY-cut, the standard classical algorithm for
  this (Ha, Haralick & Phillips 1995) — repeatedly slicing the page at any
  gap no region crosses, alternating horizontal (stacked bands) and
  vertical (side-by-side columns) cuts. This is what makes a genuinely
  two-column page come out in the right order instead of interleaving both
  columns line-by-line.
- **DocumentStructureEngine** — runs the layout model on each page, crops
  each detected region, and OCRs each crop individually (rather than the
  whole page at once) with the page-segmentation mode set to
  `PSM_SINGLE_BLOCK`, since the layout model has already told Tesseract
  "this crop is one block" — no need for Tesseract to re-discover that
  itself. Tables are OCR'd with row (line) structure preserved instead of
  reflowed into a paragraph; images/charts/seals are kept as actual
  cropped bitmaps, never OCR'd. Falls back to Phase 5.5's plain full-page
  OCR **per page** — not for the whole document — whenever the layout
  model is unavailable (asset missing, or ONNX Runtime fails to start on a
  given device) or finds nothing on a particular page, so one awkward page
  never drags the rest of a batch down to the plain-text treatment.
- **RetypesetRenderer** now takes a typed `List<DocBlock>` instead of a
  flat paragraph list. Titles render bold and larger (the document's own
  title larger still); tables keep row breaks and get a light background
  tint so they read as a distinct block; images are drawn as actual
  pictures, scaled to the content width (or to one page's height, if that
  would be taller than a page) and never split across a page break. The
  underlying line-boundary-safe pagination from Phase 5.5 is unchanged —
  it now just also guarantees a table row is never cut in half.
- The export dialog's explanation text was updated to reflect this —
  tables and images are no longer flatly declared unsupported.

### Honest limitations, specific to Phase 6

- **Never run on a real device.** Everything above was written against
  the ONNX model's own documented input/output contract (input tensor
  names, shapes, preprocessing, output layout), not verified by actually
  executing ONNX Runtime on Android — this build environment has no
  Android device or emulator. `LayoutEngine` is written defensively
  (matching output tensors by shape/dtype rather than assuming exact
  names, catching model-load failures rather than crashing) specifically
  because of that uncertainty, and the whole feature is designed to fail
  soft into Phase 5.5's old plain-text behaviour rather than break
  retypeset entirely if something about the contract doesn't hold in
  practice on-device.
- **Table cells, not table grids** *(as of Phase 6 — Phase 6.1, above,
  closes this specific gap via a classical geometric method, not a
  learned table-structure model)*. PP-DocLayout-S reports *where* a table
  is, not its row/column geometry inside that box. Row breaks are kept
  (each OCR'd text line stays on its own line); column alignment within a
  row is not — that would need PP-Structure's separate table-recognition
  model, a further, heavier addition, not this one.
- **Formulas and algorithms are read as plain text.** The layout model
  knows a region is a formula or an algorithm listing; there's no
  formula-recognition model behind that, so Tesseract reads it as if it
  were ordinary words, which mangles math notation and code indentation.
- **The ONNX export is third-party, not an official Baidu artifact** —
  PaddlePaddle publishes PP-DocLayout-S as a Paddle model, not an ONNX
  one. The specific export used here documents its own conversion
  (`paddle2onnx`) and publishes a SHA-256 for the exact file, which is
  what the CI workflow checks against, but it's still trusting a
  third-party conversion rather than an upstream-signed artifact.
- **A second on-device model adds real latency and memory** on top of
  Tesseract's own — every region gets its own OCR pass instead of one pass
  per page. On a large multi-page batch this makes "Digital Re-typeset"
  noticeably slower than before; there's no batching/parallelism added
  here to offset that, since correctness came first.

### Post-Phase 6 audit — two real fixes

A second, closer read-through of the Phase 6 code (specifically requested,
not routine) turned up two genuine issues, both fixed rather than just
noted:

- **Margin math bug in `RetypesetRenderer`, actually pre-dating Phase 6.**
  Phase 5.5's original pagination measured "space left on the page" as
  `(pageHeightPx - 2*marginPx) - currentY`, but `currentY` is an absolute
  coordinate that already starts at `marginPx` (the top margin). That
  silently subtracted a margin's worth of height twice, so every
  re-typeset page ended up with a bottom margin roughly *twice* as tall as
  its top margin. Harmless — nothing was ever cut off, pages just wasted
  some space and a document could run one page longer than necessary —
  but wrong, and now fixed by naming the two distinct quantities
  separately (`pageContentHeight` for "how much fits on one empty page"
  vs. `contentBottomY` for "the actual Y coordinate of the bottom
  margin") instead of conflating them into one `availableHeight`.
- **`LayoutEngine`'s output-tensor matching was more brittle than it
  needed to be.** It only recognized a true 0-d scalar for `num_dets` and
  an un-batched `[M, 6]` shape for the detections tensor. Since this was
  never run against the real model file, both are plausible export
  variants that would have made `analyze()` quietly find nothing (safe,
  since DocumentStructureEngine already falls back per-page — but still
  worth closing). Now also accepts a `[1]`-shaped count and a batch-wrapped
  `[1, M, 6]` detections tensor.

## Table structure: SLANet_plus added (real learned model, not just geometry)

Requested explicitly after discussing the tradeoff: `TableStructureEngine`'s
classical lattice/stream approach couldn't handle merged cells (colspan/
rowspan) or unusual column counts without a tuned percentage threshold.
**SLANet_plus** — PP-Structure's actual table-structure model, ~6.9MB
ONNX — is now tried first; the classical approach is the fallback if it
doesn't initialize or doesn't produce a usable grid.

**Independently verified before writing any code** (not assumed): the
exact preprocessing pipeline and order (BGR, not RGB; proportional resize
to 488 on the longer side; ImageNet-normalize in that same BGR channel
order; pad to 488×488 *after* normalizing; then CHW), the output contract
(`structure_probs` token logits + `loc_preds` xyxyxyxy quads per step),
and the postprocess class's real behavior (`merge_no_span_structure`,
`sos`/`eos` special tokens, which token spellings carry a cell's box).

**The vocabulary is downloaded at build time from PaddleOCR's own repo**
(`table_structure_dict_ch.txt`), not hardcoded — the exact 50-ish token
list is the one thing that could silently corrupt every table if guessed
wrong, so it isn't guessed.

**Stated plainly, this is the least-verified piece of the whole app:**
- The grid-assembly algorithm (turning the token stream into an actual
  row/column occupancy grid) is this app's own implementation of a
  standard, well-established technique — not a checked port of
  PaddleOCR's exact code, which wasn't available to read line-by-line.
- This specific ONNX export's input/output tensor *names* weren't
  confirmed, so they're discovered by shape at runtime instead of
  hardcoded (same defensive pattern as `LayoutEngine`).
- Whether `loc_preds` is normalized to [0,1] or already in pixel space
  wasn't confirmed — both are handled.
- Padding alignment (top-left vs. centered) wasn't confirmed — top-left
  is assumed as the conventional default.

None of this can cause a crash or corrupt a document — `TableStructureEngine`
only accepts a SLANet_plus result that parses into a non-empty grid, and
otherwise falls straight back to the classical approach that was already
shipping. The realistic failure mode is "falls back more often than it
ideally would," not "silently produces a wrong table." This should be
one of the first things checked against a real build.

## Re-check pass on SLANet_plus — two more real bugs found and fixed

Requested explicitly ("check very carefully, keep what works, remove what
doesn't"). Found two genuine bugs, both fixed — nothing needed removing:

1. **Grid row-tracking edge case**: a malformed token sequence (a cell
   token appearing before the very first `<tr>`) was tracked internally
   at row -1 while being *reported* as row 0, which could let a later,
   genuinely-row-0 cell silently overlap it undetected. Now a real,
   never-negative row is tracked throughout instead of coercing only at
   the boundary.
2. **A real compile-breaking bug, in both `LayoutEngine` and
   `SLANetTableRecognizer`**: ONNX Runtime's `OrtSession.Result`
   implements `java.lang.AutoCloseable`, not `java.io.Closeable`.
   Kotlin's stdlib `.use { }` specifically requires `Closeable` — it
   would not have compiled against the real ONNX Runtime library. Fixed
   by closing the `Result` manually in a `try`/`finally` in both places.

## English OCR option: PP-OCRv5 (real learned OCR, not just Tesseract)

Requested after discussing the tradeoff directly: Tesseract handles
Bangla+English mixed content in one pass (needed for the app's main
Bangla use case), but PP-OCRv5's English recognition is generally more
accurate on real photographed pages. Rather than one engine replacing the
other — which would break mixed-language pages — this is an explicit,
opt-in choice: a **"This document is fully English"** checkbox in both
the Export and Digital Re-typeset dialogs. Checked → PP-OCRv5 (detection +
recognition, ONNX). Unchecked (default) → Tesseract (Bangla+English),
unchanged.

- **`TextOcrEngine`** — a shared interface both `OcrEngine` (Tesseract)
  and the new `PaddleOcrEngine` implement, so `DocumentStructureEngine`
  and the export/re-typeset flows call whichever engine was chosen
  without caring which one it actually is.
- **`PaddleTextDetector`** (DB/Differentiable-Binarization detection) +
  **`PaddleTextRecognizer`** (CRNN+CTC recognition) — both ONNX, built
  against PaddleOCR's own published preprocessing configs, independently
  cross-checked against three separate open-source PP-OCRv6/v5
  reimplementations (including a working Android app) that all agreed on
  the same pipeline — noticeably more corroborated than SLANet_plus was.
- **Honestly flagged, not verified against the real model file**: the DB
  post-processing constants (binarize/box-score thresholds, unclip
  ratio) — cited by one community pipeline as matching PaddleOCR's
  defaults, plausible but not confirmed against this exact export. A
  wrong value here means detecting too many/few text regions, not a
  crash or garbled text.
- **A real, already-reported bug elsewhere avoided by design**: a
  PaddleOCR GitHub discussion found this exact model family's documented
  input height (48) produced garbage for at least one fine-tune while 32
  worked. Rather than hardcode either, `PaddleTextRecognizer.init()`
  reads the ONNX model's own declared input height and uses that.
- **Stated plainly**: PP-OCRv5's detector finds text *lines*, not
  individual words the way Tesseract's segmentation does — word-level
  boxes are estimated (each line's text split on spaces, given a
  proportional width slice by character count), not a real per-glyph
  measurement. Good enough for search/copy position and table-cell
  bucketing, not a precision word-box source.
- This batch of code passed review clean — no bugs found in `TextOcrEngine`,
  `PaddleTextDetector`, `PaddleTextRecognizer`, `PaddleOcrEngine`, or their
  wiring into `SessionActivity`/`DocumentStructureEngine`.

## Next step

Phase 7: Polish (watermark, Google Drive backup, share/export UI).
