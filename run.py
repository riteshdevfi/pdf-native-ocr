"""
pdf-native-ocr: DocTR bbox detection + Qwen text recognition → pipeline JSON → tagged PDF.

Usage:
    python run.py

Configure the settings below, then run. The script will:
  1. Render PDF pages to images (PyMuPDF, DPI=300)
  2. Run DocTR for word-level bounding boxes
  3. (Optional) Enhance text with Qwen VLM line-crop recognition
  4. Build Java-compatible pipeline JSON
  5. Run Java StandalonePaddleIntegrator to produce 4 tagged PDFs
"""

import os
import sys
import time
import subprocess
import numpy as np
from datetime import datetime
from pathlib import Path
from typing import List
from PIL import Image

# Load .env before any os.getenv calls
try:
    from dotenv import load_dotenv
    _env_path = os.path.join(os.path.dirname(__file__), ".env")
    if os.path.isfile(_env_path):
        load_dotenv(_env_path, override=True)
except ImportError:
    pass


# =============================================================================
# CONFIGURATION
# =============================================================================

# Input: set INPUT_FILE to process a single PDF, or INPUT_DIR to process all PDFs in a folder.
# INPUT_FILE takes priority — set it to "" to use INPUT_DIR instead.
# INPUT_FILE = "/home/ritesh_manchikanti/work/FBI-Redact/pdf-native-ocr/input/EXAMPLE 2 Barker Karpis Part 01 of 10_15pg extract.pdf"
INPUT_FILE = "/home/ritesh_manchikanti/work/FBI-Redact/pdf-native-ocr/0003.pdf"
# "/home/ritesh_manchikanti/work/FBI-Redact/pdf-native-ocr/0003.pdf"
# "/home/ritesh_manchikanti/work/FBI-Redact/pdf-native-ocr/input/EXAMPLE 3 Al Capone Vault_15pg extract.pdf"
# "/home/ritesh_manchikanti/work/FBI-Redact/pdf-native-ocr/input/EXAMPLE 1 Leave Policy Guide Vault 1_15pg extract.pdf"
# "/home/ritesh_manchikanti/work/FBI-Redact/pdf-native-ocr/input/DOGS TEST PDF.pdf"
INPUT_DIR = "/home/ritesh_manchikanti/work/accessibility/pdf-accessibility-api/files/jpeg2000_samples/JPEG2000 Compression"
# "/home/ritesh_manchikanti/work/FBI-Redact/pdf-native-ocr/input"
# Pages to process (1-based). Empty list = all pages. Applies to all files.
PAGES: List[int] = []

# Output directory (pipeline JSON + tagged PDFs go here)
OUTPUT_DIR = "./results/"
# "/home/ritesh_manchikanti/work/accessibility/pdf-accessibility-api/files/jpeg2000_samples/output_mar17"
# "/home/ritesh_manchikanti/work/FBI-Redact/pdf-native-ocr/results/march_17"
#
# "./results"

# DocTR settings
DPI = 300
DOCTR_DET_ARCH = "db_resnet50"
DOCTR_RECO_ARCH = "crnn_vgg16_bn"
DOCTR_BATCH_SIZE = 32  # pages per DocTR batch (lower = less GPU memory)

# LLM provider — reads from .env (LLM_PROVIDER, LLM_API_KEY, LLM_MODEL)
# Falls back to QWEN_* env vars, then hardcoded defaults for backward compat
LLM_PROVIDER = os.getenv("LLM_PROVIDER", "vllm").lower().strip()

_PROVIDER_URLS = {
    "claude":    "https://api.anthropic.com",
    "fireworks": "https://api.fireworks.ai/inference/v1",
    "vllm":      os.getenv("LLM_BASE_URL", "http://62.169.159.225/v1"),
    "openai":    "https://api.openai.com/v1",
}
QWEN_API_BASE = os.getenv("LLM_BASE_URL", os.getenv("QWEN_API_BASE", _PROVIDER_URLS.get(LLM_PROVIDER, "http://62.169.159.225/v1")))
QWEN_API_KEY = os.getenv("LLM_API_KEY", os.getenv("QWEN_API_KEY", "not-needed"))
QWEN_MODEL = os.getenv("LLM_MODEL", os.getenv("QWEN_MODEL", "Qwen/Qwen2.5-VL-72B-Instruct-AWQ"))
QWEN_MAX_TOKENS = int(os.getenv("LLM_MAX_TOKENS", os.getenv("QWEN_MAX_TOKENS", "512")))
# External APIs → sequential (1 worker) to avoid rate limits. Self-hosted vLLM → parallel.
_DEFAULT_WORKERS = "1" if LLM_PROVIDER != "vllm" else "15"
QWEN_MAX_WORKERS = int(os.getenv("LLM_MAX_WORKERS", os.getenv("QWEN_MAX_WORKERS", _DEFAULT_WORKERS)))
print(f"[CONFIG] LLM_PROVIDER={LLM_PROVIDER}, LLM_MAX_WORKERS={QWEN_MAX_WORKERS}, LLM_MODEL={QWEN_MODEL}")

USE_QWEN_TEXT = os.getenv("USE_QWEN_TEXT", "true").lower() in ("true", "1", "yes", "on")
USE_QWEN_FALLBACK = os.getenv("USE_QWEN_FALLBACK", "true").lower() in ("true", "1", "yes", "on")
QWEN_FALLBACK_AVG_RATIO = float(os.getenv("QWEN_FALLBACK_AVG_RATIO", "0.5"))
QWEN_FULL_OCR_MAX_TOKENS = int(os.getenv("QWEN_FULL_OCR_MAX_TOKENS", "8192"))

# Multi-orientation DocTR: runs at 0°/90°/180°/270° and merges via NMS.
# Useful for pages with mixed text orientations (e.g. rotated stamps).
# Adds 'angle' field per word in pipeline JSON output.
MULTI_ORIENTATION = os.getenv("MULTI_ORIENTATION", "false").lower() in ("true", "1", "yes", "on")

# Line grouping
Y_TOLERANCE = 15
LINE_PADDING = 5

# Java pipeline settings
ZOOM = 2.0
JAVA_PROJECT_DIR = os.path.join(os.path.dirname(__file__), "standalone_java_project")
STRIP_TEXT_LAYER = False  # Strip existing native text before Java tagging (set True for PDFs with existing OCR)

# =============================================================================
# MAIN
# =============================================================================


def render_pdf_pages(pdf_path: str, page_numbers: List[int], dpi: int = 300) -> List[Image.Image]:
    """Render PDF pages to PIL images using PyMuPDF."""
    import fitz

    doc = fitz.open(pdf_path)
    total_pages = len(doc)
    if not page_numbers:
        page_numbers = list(range(1, total_pages + 1))

    images = []
    mat = fitz.Matrix(dpi / 72.0, dpi / 72.0)
    for pn in page_numbers:
        if pn < 1 or pn > total_pages:
            print(f"  WARNING: Page {pn} out of range (1-{total_pages}), skipping")
            continue
        page = doc[pn - 1]
        pix = page.get_pixmap(matrix=mat, alpha=False)
        img = Image.frombytes("RGB", [pix.width, pix.height], pix.samples)
        images.append(img)
        print(f"  Page {pn}: {pix.width}x{pix.height}px")

    doc.close()
    return images, page_numbers


def strip_text_layer(pdf_path: str, output_path: str) -> bool:
    """Strip existing text layer from PDF by re-creating pages as image-only.
    Returns True if text was found and stripped, False if PDF was already image-only."""
    import fitz

    src = fitz.open(pdf_path)
    has_text = any(page.get_text().strip() for page in src)

    if not has_text:
        src.close()
        return False

    # Re-create each page as image-only (render at high DPI, insert as full-page image)
    dst = fitz.open()
    for page in src:
        rect = page.rect
        # Render page to image (includes both image + text as pixels)
        pix = page.get_pixmap(dpi=300)
        # Create new blank page with same dimensions
        new_page = dst.new_page(width=rect.width, height=rect.height)
        # Insert rendered image as full-page background
        new_page.insert_image(rect, pixmap=pix)

    dst.save(output_path)
    dst.close()
    src.close()
    print(f"  Stripped existing text layer → {Path(output_path).name}")
    return True


def wrap_content_streams(pdf_path: str, output_path: str) -> bool:
    """Wrap each page's content streams in q/Q (save/restore graphics state).
    This isolates existing transforms (like y-flips) so they don't leak into
    new content streams added by the Java tagging app.
    Returns True if any page was fixed."""
    import fitz

    doc = fitz.open(pdf_path)
    fixed = False
    for page in doc:
        xrefs = page.get_contents()
        if not xrefs:
            continue
        # Check if existing content has an unguarded cm transform
        first_stream = doc.xref_stream(xrefs[0]).decode("latin-1", errors="replace")
        if "cm" in first_stream[:200] and not first_stream.lstrip().startswith("q"):
            # Wrap all content streams for this page in q/Q
            page.clean_contents()  # merges multiple streams into one
            xrefs = page.get_contents()
            for xref in xrefs:
                stream = doc.xref_stream(xref)
                doc.update_stream(xref, b"q\n" + stream + b"\nQ\n")
            fixed = True

    if fixed:
        doc.save(output_path)
        print(f"  Wrapped content streams (isolated transforms) → {Path(output_path).name}")
    doc.close()
    return fixed


def _build_java_jar():
    """Build the Maven project if jar doesn't exist. Returns jar path or None."""
    jar_path = os.path.join(JAVA_PROJECT_DIR, "target", "standalone-paddle-integration-1.0.0.jar")
    if not os.path.isfile(jar_path):
        print("  Building Maven project...")
        result = subprocess.run(
            ["mvn", "-q", "-DskipTests", "package"],
            cwd=JAVA_PROJECT_DIR,
            capture_output=True, text=True,
        )
        if result.returncode != 0:
            print(f"  Maven build failed: {result.stderr[:500]}")
            return None
    return jar_path


def _run_java_jar(jar_path: str, json_path: str, pdf_path: str, output_pdf: str, visible: bool):
    """Run the Java jar with visible or invisible text rendering."""
    cmd = ["java"]
    if visible:
        cmd.append("-Docr.words.visible=true")
    cmd.extend(["-jar", jar_path, json_path, pdf_path, output_pdf])

    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode == 0:
        label = "visible" if visible else "invisible"
        print(f"  {label}: {output_pdf}")
    else:
        print(f"  Java tagging failed (exit {result.returncode})")
        if result.stderr:
            print(f"  stderr: {result.stderr[:500]}")
    return result.returncode == 0


def run_java_pipeline(json_path: str, pdf_path: str, output_dir: str, tag: str = "qwen"):
    """Run Java StandalonePaddleIntegrator to generate visible and invisible tagged PDFs."""
    if not os.path.isdir(JAVA_PROJECT_DIR):
        print(f"  WARNING: Java project not found at {JAVA_PROJECT_DIR}")
        print("  Skipping PDF tagging. Copy standalone_java_project/ to use this feature.")
        return

    jar_path = _build_java_jar()
    if not jar_path:
        return

    # Convert to absolute paths to avoid cwd issues
    json_path = os.path.abspath(json_path)
    pdf_path = os.path.abspath(pdf_path)
    abs_out = os.path.abspath(output_dir)

    pdf_name = Path(pdf_path).stem
    invisible_pdf = os.path.join(abs_out, f"{pdf_name}_{tag}_invisible.pdf")
    visible_pdf = os.path.join(abs_out, f"{pdf_name}_{tag}_visible.pdf")

    _run_java_jar(jar_path, json_path, pdf_path, invisible_pdf, visible=False)
    _run_java_jar(jar_path, json_path, pdf_path, visible_pdf, visible=True)

    # Uncomment below to keep bounding_box PDFs (Java auto-generates them)
    import glob
    for bb_file in glob.glob(os.path.join(abs_out, "*_bounding_box.pdf")):
        os.remove(bb_file)


def _save_annotated_images(images, ocr_pages, page_numbers, pages_dir):
    """Save page images with DocTR bounding boxes drawn on them."""
    import cv2

    for i, (img, ocr_page) in enumerate(zip(images, ocr_pages)):
        pn = page_numbers[i]
        img_cv = cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)

        for word in ocr_page["words"]:
            x1, y1, x2, y2 = word["bbox"]
            conf = word["confidence"]
            green = int(conf * 255)
            red = int((1 - conf) * 255)
            color = (0, green, red)
            cv2.rectangle(img_cv, (x1, y1), (x2, y2), color, 2)

            label = f"{word['text']} ({conf:.2f})"
            font_scale = 0.4
            (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, font_scale, 1)
            cv2.rectangle(img_cv, (x1, y1 - th - 4), (x1 + tw, y1), color, -1)
            cv2.putText(img_cv, label, (x1, y1 - 2),
                        cv2.FONT_HERSHEY_SIMPLEX, font_scale, (255, 255, 255), 1)

        out_path = os.path.join(pages_dir, f"page_{pn}_annotated.png")
        cv2.imwrite(out_path, img_cv)
    print(f"  Saved annotated images to {pages_dir}")


def process_pdf(pdf_path: str, predictor, qwen_client):
    """Process a single PDF through the full pipeline."""
    total_start = time.perf_counter()
    pdf_name = Path(pdf_path).stem
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_dir = os.path.join(OUTPUT_DIR, f"{pdf_name}_{timestamp}")
    os.makedirs(out_dir, exist_ok=True)

    page_numbers = PAGES if PAGES else []

    # ── Step 1: Render PDF → images ──────────────────────────────
    print(f"\n{'='*60}")
    print("Step 1: Rendering PDF pages")
    print(f"{'='*60}")

    images, page_numbers = render_pdf_pages(pdf_path, page_numbers, dpi=DPI)
    if not images:
        print("ERROR: No pages rendered.")
        return

    print(f"  Rendered {len(images)} pages at DPI={DPI}")

    # Save raw page images
    pages_dir = os.path.join(out_dir, "pages")
    os.makedirs(pages_dir, exist_ok=True)
    for i, img in enumerate(images):
        pn = page_numbers[i]
        img.save(os.path.join(pages_dir, f"page_{pn}.png"))
    print(f"  Saved page images to {pages_dir}")

    # ── Step 2: DocTR word detection ─────────────────────────────
    print(f"\n{'='*60}")
    print("Step 2: Running DocTR word detection")
    print(f"{'='*60}")

    if MULTI_ORIENTATION:
        from ocr.doctr_ocr import run_doctr_multi_orientation
        print(f"  Mode: multi-orientation (0°/90°/180°/270° + NMS)")
        ocr_pages, doctr_time = run_doctr_multi_orientation(predictor, images, batch_size=DOCTR_BATCH_SIZE)
    else:
        from ocr.doctr_ocr import run_doctr
        ocr_pages, doctr_time = run_doctr(predictor, images, batch_size=DOCTR_BATCH_SIZE)
    total_words = sum(len(p["words"]) for p in ocr_pages)
    print(f"  DocTR: {total_words} words in {doctr_time:.1f}s")

    # Check which pages need Qwen full OCR fallback (below-average word count)
    qwen_fallback_pages = set()
    if USE_QWEN_FALLBACK and qwen_client:
        page_word_counts = [len(p["words"]) for p in ocr_pages]
        avg_words = sum(page_word_counts) / max(len(page_word_counts), 1)
        fallback_threshold = avg_words * QWEN_FALLBACK_AVG_RATIO
        print(f"  Avg words/page: {avg_words:.0f}, fallback threshold: {fallback_threshold:.0f} ({QWEN_FALLBACK_AVG_RATIO:.0%} of avg)")
        for i, word_count in enumerate(page_word_counts):
            if word_count < fallback_threshold:
                pn = page_numbers[i]
                qwen_fallback_pages.add(i)
                print(f"  Page {pn}: only {word_count} words (< {fallback_threshold:.0f}) → will use Qwen full OCR")

    # Run Qwen full OCR on fallback pages (all strips across all pages in parallel)
    if qwen_fallback_pages:
        from ocr.qwen_ocr import _qwen_ocr_single, _save_strip_debug, _deduplicate_words
        from concurrent.futures import ThreadPoolExecutor, as_completed

        NUM_STRIPS = 3
        STRIP_OVERLAP = 50
        strips_dir = os.path.join(pages_dir, "qwen_strips")

        # Build all strips across all fallback pages
        all_strip_tasks = []  # (page_idx, strip_idx, strip_img, y_start, y_end, page_num)
        for i in qwen_fallback_pages:
            pn = page_numbers[i]
            img = images[i]
            img_w, img_h = img.size
            strip_height = img_h // NUM_STRIPS
            for s in range(NUM_STRIPS):
                y_start = max(0, s * strip_height - STRIP_OVERLAP)
                y_end = min(img_h, (s + 1) * strip_height + STRIP_OVERLAP) if s < NUM_STRIPS - 1 else img_h
                strip_img = img.crop((0, y_start, img_w, y_end))
                all_strip_tasks.append((i, s, strip_img, y_start, y_end, pn))

        print(f"\n  Running Qwen full OCR: {len(qwen_fallback_pages)} page(s), {len(all_strip_tasks)} strips (max {QWEN_MAX_WORKERS} parallel)...")
        qwen_ocr_start = time.perf_counter()

        # Run all strips in parallel
        strip_results = {}  # (page_idx, strip_idx) → words
        with ThreadPoolExecutor(max_workers=QWEN_MAX_WORKERS) as pool:
            futures = {}
            for task in all_strip_tasks:
                page_idx, s, strip_img, y_start, y_end, pn = task
                future = pool.submit(
                    _qwen_ocr_single,
                    image=strip_img,
                    client=qwen_client,
                    model_name=QWEN_MODEL,
                    max_tokens=QWEN_FULL_OCR_MAX_TOKENS,
                    y_offset=y_start,
                )
                futures[future] = task

            for future in as_completed(futures):
                page_idx, s, strip_img, y_start, y_end, pn = futures[future]
                words = future.result()
                strip_results[(page_idx, s)] = words
                print(f"    Page {pn} strip {s+1}/{NUM_STRIPS} (y={y_start}-{y_end}): {len(words)} words")
                _save_strip_debug(strip_img, words, strips_dir, pn, s, y_start)

        # Assemble strips back into pages
        for i in qwen_fallback_pages:
            pn = page_numbers[i]
            page_words = []
            for s in range(NUM_STRIPS):
                strip_words = strip_results.get((i, s), [])
                page_words.extend(strip_words)
            # Deduplicate overlap
            if STRIP_OVERLAP > 0 and len(page_words) > 1:
                page_words = _deduplicate_words(page_words, iou_threshold=0.3)
            if page_words:
                ocr_pages[i]["words"] = page_words
                print(f"  Page {pn}: Qwen full OCR → {len(page_words)} words")
            else:
                print(f"  Page {pn}: Qwen full OCR failed, keeping DocTR ({len(ocr_pages[i]['words'])} words)")

        qwen_ocr_time = time.perf_counter() - qwen_ocr_start
        print(f"  Qwen full OCR done in {qwen_ocr_time:.1f}s")

    # Save annotated page images with bounding boxes
    _save_annotated_images(images, ocr_pages, page_numbers, pages_dir)

    # Save DocTR-only results (before Qwen line enhancement) for comparison
    import copy
    from converter.to_pipeline_json import build_pipeline_json, save_pipeline_json

    doctr_only_pages = copy.deepcopy(ocr_pages)
    doctr_json = build_pipeline_json(
        pdf_path=pdf_path, ocr_pages=doctr_only_pages,
        page_numbers=page_numbers, dpi=DPI, zoom=ZOOM, y_tolerance=Y_TOLERANCE,
    )
    doctr_json["metadata"]["ocr_method"] = "doctr_only"
    doctr_json_path = os.path.join(out_dir, f"{pdf_name}_doctr_only.json")
    save_pipeline_json(doctr_json, doctr_json_path)

    # ── Step 3: Qwen text enhancement (optional) ────────────────
    # Only enhance pages that used DocTR (skip pages that already used Qwen full OCR)
    if USE_QWEN_TEXT and qwen_client:
        print(f"\n{'='*60}")
        print("Step 3: Enhancing text with Qwen VLM")
        print(f"{'='*60}")

        from ocr.qwen_text import enhance_with_qwen

        total_success = 0
        total_fail = 0
        qwen_start = time.perf_counter()

        for i, (ocr_page, img) in enumerate(zip(ocr_pages, images)):
            pn = page_numbers[i]

            # Skip pages that already used Qwen full OCR
            if i in qwen_fallback_pages:
                print(f"  Page {pn}: skipping (already used Qwen full OCR)")
                continue

            print(f"  Page {pn}: {len(ocr_page['words'])} words → Qwen...", end=" ")

            enhanced_words, ok, fail = enhance_with_qwen(
                image=img,
                words=ocr_page["words"],
                client=qwen_client,
                model_name=QWEN_MODEL,
                max_tokens=QWEN_MAX_TOKENS,
                max_workers=QWEN_MAX_WORKERS,
                y_tolerance=Y_TOLERANCE,
                line_padding=LINE_PADDING,
            )
            ocr_page["words"] = enhanced_words
            total_success += ok
            total_fail += fail
            print(f"{ok} lines OK, {fail} failed")

        qwen_time = time.perf_counter() - qwen_start
        print(f"  Qwen total: {total_success} lines enhanced, {total_fail} failed in {qwen_time:.1f}s")
    else:
        print(f"\n  Skipping Qwen text enhancement (USE_QWEN_TEXT=False)")

    # ── Step 4: Build pipeline JSON (with Qwen text) ────────────
    print(f"\n{'='*60}")
    print("Step 4: Building pipeline JSON")
    print(f"{'='*60}")

    pipeline_data = build_pipeline_json(
        pdf_path=pdf_path,
        ocr_pages=ocr_pages,
        page_numbers=page_numbers,
        dpi=DPI,
        zoom=ZOOM,
        y_tolerance=Y_TOLERANCE,
    )

    json_path = os.path.join(out_dir, f"{pdf_name}_raw_results.json")
    save_pipeline_json(pipeline_data, json_path)

    from converter.to_text_json import pipeline_json_to_text, save_text_json
    text_data = pipeline_json_to_text(pipeline_data)
    text_json_path = os.path.join(out_dir, f"{pdf_name}_text.json")
    save_text_json(text_data, text_json_path)
    print(f"  Saved text JSON: {text_json_path}")

    # ── Step 5: Java PDF tagging ─────────────────────────────────
    print(f"\n{'='*60}")
    print("Step 5: Running Java PDF tagging")
    print(f"{'='*60}")

    # Strip existing text layer if enabled (fixes PDFs that already have native text)
    java_pdf_path = pdf_path
    if STRIP_TEXT_LAYER:
        stripped_path = os.path.join(out_dir, f"{pdf_name}_stripped.pdf")
        if strip_text_layer(pdf_path, stripped_path):
            java_pdf_path = stripped_path
        else:
            print("  No existing text layer found, using original PDF")

    # Wrap existing content streams in q/Q to isolate transforms (fixes y-flipped PDFs)
    wrapped_path = os.path.join(out_dir, f"{pdf_name}_wrapped.pdf")
    if wrap_content_streams(java_pdf_path, wrapped_path):
        java_pdf_path = wrapped_path

    print("  DocTR-only PDFs:")
    run_java_pipeline(doctr_json_path, java_pdf_path, out_dir, tag="doctr")
    print("  Qwen-enhanced PDFs:")
    run_java_pipeline(json_path, java_pdf_path, out_dir, tag="qwen")

    total_time = time.perf_counter() - total_start
    print(f"\n  Results: {out_dir}")
    print(f"  Total time: {total_time:.1f}s")


def main():
    # Resolve input: INPUT_FILE takes priority over INPUT_DIR
    if INPUT_FILE and os.path.isfile(INPUT_FILE):
        pdf_files = [Path(INPUT_FILE)]
    else:
        # Filter to specific files (empty list = all files in INPUT_DIR)
        ONLY_FILES = [
            "MET Lab Reports Vol 20 79-33-90 (Image-Only & JPEG2000 Compression).pdf",
            "Metallurigical Report July 1967 Vol 5 (Image-Only & JPEG2000 Compression).pdf",
            "Report No 00-001 thru 00-050 Vol 81 (Image-Only & JPEG2000 Compression).pdf",
            "Vol 33 86-28 to 86-64 (Image-Only & JPEG2000 Compression).pdf",
        ]
        all_pdfs = sorted(Path(INPUT_DIR).glob("*.pdf"))
        if ONLY_FILES:
            pdf_files = [p for p in all_pdfs if p.name in ONLY_FILES]
        else:
            pdf_files = all_pdfs

    if not pdf_files:
        print(f"ERROR: No PDF files found. Check INPUT_FILE or INPUT_DIR.")
        sys.exit(1)

    print(f"Found {len(pdf_files)} PDF(s):")
    for f in pdf_files:
        print(f"  - {f.name}")

    # Load DocTR model once (reused across all files)
    print(f"\n{'='*60}")
    print("Loading DocTR model...")
    print(f"{'='*60}")

    from ocr.doctr_ocr import load_doctr_model
    predictor, device = load_doctr_model(
        det_arch=DOCTR_DET_ARCH,
        reco_arch=DOCTR_RECO_ARCH,
    )

    # Create Qwen client once
    qwen_client = None
    if USE_QWEN_TEXT or USE_QWEN_FALLBACK:
        from ocr.llm_client import create_llm_client
        qwen_client = create_llm_client(provider=LLM_PROVIDER, api_base=QWEN_API_BASE, api_key=QWEN_API_KEY)

    # Process each PDF
    for i, pdf_file in enumerate(pdf_files):
        print(f"\n{'#'*60}")
        print(f"# FILE {i+1}/{len(pdf_files)}: {pdf_file.name}")
        print(f"{'#'*60}")

        try:
            process_pdf(str(pdf_file), predictor, qwen_client)
        except Exception as e:
            print(f"  ERROR processing {pdf_file.name}: {e}")
            continue

    print(f"\n{'='*60}")
    print(f"All done! Processed {len(pdf_files)} file(s).")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
