"""
FastAPI wrapper for pdf-native-ocr pipeline with async job queue.

Usage:
    uvicorn api:app --host 0.0.0.0 --port 8000

Endpoints:
    POST /ocr              — Submit PDF, get job_id immediately
    GET  /ocr/job/{job_id} — Poll job status + results
    GET  /ocr/job/{job_id}/download — Download tagged PDF
    GET  /health           — Check if models are loaded
    GET  /queue/status     — Queue info (pending, running, max)
"""

import os
import uuid
import time
import copy
import json
import logging
import shutil
import threading
import traceback
import numpy as np
from queue import Queue
from pathlib import Path
from datetime import datetime
from typing import Dict
from contextlib import asynccontextmanager
from concurrent.futures import ThreadPoolExecutor
from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import JSONResponse, FileResponse
from PIL import Image

# Load .env before any os.getenv calls
try:
    from dotenv import load_dotenv
    _env_path = os.path.join(os.path.dirname(__file__), ".env")
    if os.path.isfile(_env_path):
        load_dotenv(_env_path, override=True)
except ImportError:
    pass

from run import (
    render_pdf_pages, strip_text_layer, wrap_content_streams,
    _save_annotated_images, _build_java_jar, _run_java_jar,
    JAVA_PROJECT_DIR,
)
from converter.to_text_json import pipeline_json_to_text

# Logging setup
LOG_DIR = os.path.join(os.path.dirname(__file__), "logs")
os.makedirs(LOG_DIR, exist_ok=True)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.StreamHandler(),
        logging.FileHandler(os.path.join(LOG_DIR, "api.log")),
    ],
)
logger = logging.getLogger("pdf-native-ocr")

# Suppress noisy HTTP request logs from openai/httpx
for _noisy in ("httpx", "openai", "httpcore", "openai._base_client", "httpx._client"):
    logging.getLogger(_noisy).setLevel(logging.WARNING)


# =============================================================================
# CONFIGURATION — edit these values directly, no request params needed
# =============================================================================
DPI = 300
DOCTR_DET_ARCH = "db_resnet50"
DOCTR_RECO_ARCH = "crnn_vgg16_bn"

# LLM provider — reads from .env (LLM_PROVIDER, LLM_API_KEY, LLM_MODEL)
# Falls back to QWEN_* env vars, then hardcoded defaults for backward compat
LLM_PROVIDER = os.getenv("LLM_PROVIDER", "vllm").lower().strip()

# Provider → base URL mapping
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

USE_QWEN_TEXT = os.getenv("USE_QWEN_TEXT", "true").lower() in ("true", "1", "yes", "on")
USE_QWEN_FALLBACK = os.getenv("USE_QWEN_FALLBACK", "false").lower() in ("true", "1", "yes", "on")
QWEN_FALLBACK_AVG_RATIO = float(os.getenv("QWEN_FALLBACK_AVG_RATIO", "0.5"))
QWEN_FULL_OCR_MAX_TOKENS = int(os.getenv("QWEN_FULL_OCR_MAX_TOKENS", "8192"))

Y_TOLERANCE = 15
LINE_PADDING = 5
ZOOM = 2.0
OUTPUT_DIR = "./logs/jobs"
STRIP_TEXT_LAYER = False
SAVE_DEBUG = True

MAX_CONCURRENT_JOBS = 2   # Max jobs running at the same time
UPLOAD_DIR = "./logs/uploads"   # Temp storage for uploaded PDFs


# =============================================================================
# JOB MANAGER
# =============================================================================

class JobManager:
    """Thread-safe in-memory job queue with bounded concurrency."""

    def __init__(self, max_concurrent: int = 2):
        self._jobs: Dict[str, dict] = {}
        self._lock = threading.Lock()
        self._queue = Queue()
        self._max_concurrent = max_concurrent
        self._running_count = 0
        self._running_lock = threading.Lock()

        # Start background worker thread
        self._worker = threading.Thread(target=self._process_queue, daemon=True)
        self._worker.start()

    def create_job(self, filename: str, pdf_path: str) -> str:
        job_id = str(uuid.uuid4())
        with self._lock:
            self._jobs[job_id] = {
                "job_id": job_id,
                "filename": filename,
                "pdf_path": pdf_path,
                "status": "queued",
                "progress": "Queued for processing",
                "created_at": datetime.now().isoformat(),
                "started_at": None,
                "completed_at": None,
                "result": None,
                "error": None,
                "output_dir": None,
                "tagged_pdfs": None,
                "text_json_path": None,
                "total_words": None,
                "total_time": None,
                "pages_processed": None,
            }
        self._queue.put(job_id)
        return job_id

    def get_job(self, job_id: str) -> dict:
        with self._lock:
            job = self._jobs.get(job_id)
            if job is None:
                return None
            return dict(job)  # Return a copy

    def update_job(self, job_id: str, **kwargs):
        with self._lock:
            if job_id in self._jobs:
                self._jobs[job_id].update(kwargs)

    def get_queue_status(self) -> dict:
        with self._running_lock:
            running = self._running_count
        return {
            "queue_size": self._queue.qsize(),
            "running_jobs": running,
            "max_concurrent": self._max_concurrent,
        }

    def _process_queue(self):
        """Background worker: pull jobs from queue and process them."""
        while True:
            job_id = self._queue.get()

            # Wait until a slot is available
            while True:
                with self._running_lock:
                    if self._running_count < self._max_concurrent:
                        self._running_count += 1
                        break
                time.sleep(0.5)

            # Process in a thread
            thread = threading.Thread(
                target=self._run_job, args=(job_id,), daemon=True
            )
            thread.start()

    def _run_job(self, job_id: str):
        """Execute a single job."""
        try:
            job = self.get_job(job_id)
            if job is None:
                return

            self.update_job(job_id,
                status="processing",
                progress="Starting OCR pipeline",
                started_at=datetime.now().isoformat(),
            )

            # Run the pipeline
            result = _process_pdf(job_id, job["pdf_path"])

            # Run Java tagging
            self.update_job(job_id, progress="Tagging PDF")
            saved = _save_and_tag_pdf(result)

            self.update_job(job_id,
                status="completed",
                progress="Complete",
                completed_at=datetime.now().isoformat(),
                output_dir=result["output_dir"],
                tagged_pdfs=saved["tagged_pdfs"],
                text_json_path=saved["text_json_path"],
                total_words=result["total_words"],
                total_time=result["total_time"],
                pages_processed=result["pages_processed"],
            )
            logger.info(f"Job {job_id[:8]} completed: {job['filename']}")

        except Exception as e:
            logger.error(f"Job {job_id[:8]} failed: {e}")
            self.update_job(job_id,
                status="failed",
                progress="Failed",
                error=str(e),
                completed_at=datetime.now().isoformat(),
            )
        finally:
            with self._running_lock:
                self._running_count -= 1


# =============================================================================
# GLOBAL STATE
# =============================================================================
models = {}
job_manager: JobManager = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global job_manager

    os.makedirs(UPLOAD_DIR, exist_ok=True)

    print("Loading DocTR model...")
    from ocr.doctr_ocr import load_doctr_model
    predictor, device = load_doctr_model(
        det_arch=DOCTR_DET_ARCH,
        reco_arch=DOCTR_RECO_ARCH,
    )
    models["predictor"] = predictor
    models["device"] = device

    from ocr.llm_client import create_llm_client
    models["qwen_client"] = create_llm_client(provider=LLM_PROVIDER, api_base=QWEN_API_BASE, api_key=QWEN_API_KEY)

    job_manager = JobManager(max_concurrent=MAX_CONCURRENT_JOBS)

    print(f"Models loaded. Device: {device}. Max concurrent jobs: {MAX_CONCURRENT_JOBS}")
    yield
    models.clear()


app = FastAPI(
    title="pdf-native-ocr",
    description="DocTR + Qwen OCR pipeline API with async job queue",
    lifespan=lifespan,
)


# =============================================================================
# CORE PROCESSING
# =============================================================================

def _process_pdf(job_id: str, pdf_path: str) -> dict:
    """Run the full OCR pipeline on a PDF."""
    total_start = time.perf_counter()
    predictor = models["predictor"]
    qwen_client = models["qwen_client"]

    pdf_name = Path(pdf_path).stem
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_dir = os.path.join(OUTPUT_DIR, f"{pdf_name}_{timestamp}")
    os.makedirs(out_dir, exist_ok=True)

    logger.info(f"[{job_id[:8]}] Processing: {pdf_name}")

    # Step 1: Render
    job_manager.update_job(job_id, progress="Rendering PDF pages")
    images, page_numbers = render_pdf_pages(pdf_path, [], dpi=DPI)
    if not images:
        raise ValueError("No pages rendered from PDF")
    logger.info(f"[{job_id[:8]}] Rendered {len(images)} pages")

    if SAVE_DEBUG:
        pages_dir = os.path.join(out_dir, "pages")
        os.makedirs(pages_dir, exist_ok=True)
        for i, img in enumerate(images):
            img.save(os.path.join(pages_dir, f"page_{page_numbers[i]}.png"))

    # Step 2: DocTR
    job_manager.update_job(job_id, progress="Running DocTR detection")
    from ocr.doctr_ocr import run_doctr
    ocr_pages, doctr_time = run_doctr(predictor, images)
    total_words = sum(len(p["words"]) for p in ocr_pages)
    logger.info(f"[{job_id[:8]}] DocTR: {total_words} words in {doctr_time:.1f}s")

    # Qwen full OCR fallback
    qwen_fallback_pages = set()
    if USE_QWEN_FALLBACK:
        page_word_counts = [len(p["words"]) for p in ocr_pages]
        avg_words = sum(page_word_counts) / max(len(page_word_counts), 1)
        fallback_threshold = avg_words * QWEN_FALLBACK_AVG_RATIO
        for i, word_count in enumerate(page_word_counts):
            if word_count < fallback_threshold:
                qwen_fallback_pages.add(i)

        if qwen_fallback_pages:
            job_manager.update_job(job_id, progress=f"Qwen full OCR ({len(qwen_fallback_pages)} pages)")
            from ocr.qwen_ocr import _qwen_ocr_single, _deduplicate_words
            from concurrent.futures import ThreadPoolExecutor, as_completed

            NUM_STRIPS = 3
            STRIP_OVERLAP = 50
            all_strip_tasks = []
            for i in qwen_fallback_pages:
                img = images[i]
                img_w, img_h = img.size
                strip_height = img_h // NUM_STRIPS
                for s in range(NUM_STRIPS):
                    y_start = max(0, s * strip_height - STRIP_OVERLAP)
                    y_end = min(img_h, (s + 1) * strip_height + STRIP_OVERLAP) if s < NUM_STRIPS - 1 else img_h
                    strip_img = img.crop((0, y_start, img_w, y_end))
                    all_strip_tasks.append((i, s, strip_img, y_start))

            strip_results = {}
            with ThreadPoolExecutor(max_workers=QWEN_MAX_WORKERS) as pool:
                futures = {}
                for task in all_strip_tasks:
                    page_idx, s, strip_img, y_start = task
                    future = pool.submit(
                        _qwen_ocr_single, image=strip_img, client=qwen_client,
                        model_name=QWEN_MODEL, max_tokens=QWEN_FULL_OCR_MAX_TOKENS,
                        y_offset=y_start,
                    )
                    futures[future] = task
                for future in as_completed(futures):
                    page_idx, s, strip_img, y_start = futures[future]
                    strip_results[(page_idx, s)] = future.result()

            for i in qwen_fallback_pages:
                page_words = []
                for s in range(NUM_STRIPS):
                    page_words.extend(strip_results.get((i, s), []))
                if STRIP_OVERLAP > 0 and len(page_words) > 1:
                    page_words = _deduplicate_words(page_words, iou_threshold=0.3)
                if page_words:
                    ocr_pages[i]["words"] = page_words

    # Save DocTR-only JSON
    from converter.to_pipeline_json import build_pipeline_json
    doctr_only_pages = copy.deepcopy(ocr_pages)
    doctr_json = build_pipeline_json(
        pdf_path=pdf_path, ocr_pages=doctr_only_pages,
        page_numbers=page_numbers, dpi=DPI, zoom=ZOOM, y_tolerance=Y_TOLERANCE,
    )
    doctr_json["metadata"]["ocr_method"] = "doctr_only"

    if SAVE_DEBUG:
        _save_annotated_images(images, ocr_pages, page_numbers, pages_dir)

    # Step 3: Qwen text enhancement
    if USE_QWEN_TEXT:
        job_manager.update_job(job_id, progress="Qwen text enhancement")
        logger.info(f"[{job_id[:8]}] Qwen text enhancement ({QWEN_MAX_WORKERS} workers)")
        from ocr.qwen_text import enhance_with_qwen
        for i, (ocr_page, img) in enumerate(zip(ocr_pages, images)):
            if i in qwen_fallback_pages:
                continue
            enhanced_words, ok, fail = enhance_with_qwen(
                image=img, words=ocr_page["words"], client=qwen_client,
                model_name=QWEN_MODEL, max_tokens=QWEN_MAX_TOKENS,
                max_workers=QWEN_MAX_WORKERS, y_tolerance=Y_TOLERANCE,
                line_padding=LINE_PADDING,
            )
            ocr_page["words"] = enhanced_words

    # Step 4: Build pipeline JSON
    job_manager.update_job(job_id, progress="Building pipeline JSON")
    pipeline_json = build_pipeline_json(
        pdf_path=pdf_path, ocr_pages=ocr_pages,
        page_numbers=page_numbers, dpi=DPI, zoom=ZOOM, y_tolerance=Y_TOLERANCE,
    )

    # Build text JSON from pipeline JSON
    text_json = pipeline_json_to_text(pipeline_json)

    total_time = time.perf_counter() - total_start
    total_words = sum(len(p["words"]) for p in ocr_pages)

    logger.info(f"[{job_id[:8]}] Done: {len(images)} pages | {total_words} words | {total_time:.1f}s")

    return {
        "pipeline_json": pipeline_json,
        "doctr_json": doctr_json,
        "text_json": text_json,
        "output_dir": out_dir,
        "pdf_path": pdf_path,
        "total_time": round(total_time, 2),
        "pages_processed": len(images),
        "total_words": total_words,
        "page_numbers": page_numbers,
    }


def _save_and_tag_pdf(result: dict) -> dict:
    """Save JSONs and run Java tagging. Returns paths to generated files."""
    out_dir = result["output_dir"]
    pdf_path = result["pdf_path"]
    pdf_name = Path(pdf_path).stem

    pipeline_json_path = os.path.join(out_dir, f"{pdf_name}_raw_results.json")
    doctr_json_path = os.path.join(out_dir, f"{pdf_name}_doctr_only.json")
    text_json_path = os.path.join(out_dir, f"{pdf_name}_text.json")
    with open(pipeline_json_path, "w") as f:
        json.dump(result["pipeline_json"], f, indent=2)
    with open(doctr_json_path, "w") as f:
        json.dump(result["doctr_json"], f, indent=2)
    with open(text_json_path, "w") as f:
        json.dump(result["text_json"], f, indent=2, ensure_ascii=False)

    java_pdf_path = pdf_path
    if STRIP_TEXT_LAYER:
        stripped_path = os.path.join(out_dir, f"{pdf_name}_stripped.pdf")
        if strip_text_layer(pdf_path, stripped_path):
            java_pdf_path = stripped_path

    wrapped_path = os.path.join(out_dir, f"{pdf_name}_wrapped.pdf")
    if wrap_content_streams(java_pdf_path, wrapped_path):
        java_pdf_path = wrapped_path

    tagged_pdfs = {}
    jar_path = _build_java_jar()
    if jar_path:
        abs_out = os.path.abspath(out_dir)
        java_pdf_abs = os.path.abspath(java_pdf_path)

        for tag, json_p in [("qwen", pipeline_json_path), ("doctr", doctr_json_path)]:
            for visible in [False, True]:
                label = "visible" if visible else "invisible"
                out_pdf = os.path.join(abs_out, f"{pdf_name}_{tag}_{label}.pdf")
                ok = _run_java_jar(jar_path, os.path.abspath(json_p), java_pdf_abs, out_pdf, visible)
                if ok:
                    tagged_pdfs[f"{tag}_{label}"] = out_pdf

        import glob
        for bb_file in glob.glob(os.path.join(abs_out, "*_bounding_box.pdf")):
            os.remove(bb_file)

    return {"tagged_pdfs": tagged_pdfs, "text_json_path": text_json_path}


# =============================================================================
# ENDPOINTS
# =============================================================================

@app.get("/health")
async def health():
    return {
        "status": "ok" if "predictor" in models else "loading",
        "device": models.get("device", "unknown"),
    }


@app.get("/queue/status")
async def queue_status():
    """Get queue info: pending, running, max concurrent."""
    return job_manager.get_queue_status()


@app.post("/ocr")
async def submit_ocr(file: UploadFile = File(...)):
    """Upload a PDF → get job_id immediately. Poll /ocr/job/{job_id} for results."""
    if not file.filename.lower().endswith(".pdf"):
        raise HTTPException(status_code=400, detail="File must be a PDF")

    # Save uploaded PDF to persistent location (not temp — job needs it later)
    os.makedirs(UPLOAD_DIR, exist_ok=True)
    file_id = str(uuid.uuid4())[:8]
    pdf_path = os.path.join(UPLOAD_DIR, f"{file_id}_{file.filename}")
    content = await file.read()
    with open(pdf_path, "wb") as f:
        f.write(content)

    job_id = job_manager.create_job(filename=file.filename, pdf_path=pdf_path)

    logger.info(f"Job submitted: {job_id[:8]} | {file.filename}")

    return JSONResponse(content={
        "job_id": job_id,
        "status": "queued",
        "poll_url": f"/ocr/job/{job_id}",
        "download_url": f"/ocr/job/{job_id}/download",
        "text_url": f"/ocr/job/{job_id}/text",
    })


@app.get("/ocr/job/{job_id}")
async def get_job_status(job_id: str):
    """Poll job status. Returns JSON while processing, PDF file when completed."""
    job = job_manager.get_job(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="Job not found")

    # When completed, return the tagged PDF directly
    if job["status"] == "completed":
        tagged = job.get("tagged_pdfs", {})
        pdf_path = tagged.get("qwen_invisible")
        if pdf_path and os.path.isfile(pdf_path):
            pdf_filename = Path(job["filename"]).stem + "_tagged.pdf"
            return FileResponse(
                pdf_path,
                media_type="application/pdf",
                filename=pdf_filename,
            )

    # Otherwise return JSON status
    response = {
        "job_id": job["job_id"],
        "filename": job["filename"],
        "status": job["status"],
        "progress": job["progress"],
        "created_at": job["created_at"],
        "started_at": job["started_at"],
        "completed_at": job["completed_at"],
    }

    if job["status"] == "failed":
        response["error"] = job["error"]

    return JSONResponse(content=response)


@app.get("/ocr/job/{job_id}/download")
async def download_tagged_pdf(job_id: str):
    """Download the invisible-text tagged PDF for a completed job."""
    job = job_manager.get_job(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="Job not found")

    if job["status"] != "completed":
        raise HTTPException(status_code=400, detail=f"Job not complete (status: {job['status']})")

    tagged = job.get("tagged_pdfs", {})
    pdf_path = tagged.get("qwen_invisible")
    if not pdf_path or not os.path.isfile(pdf_path):
        raise HTTPException(status_code=500, detail="Tagged PDF not found")

    pdf_filename = Path(job["filename"]).stem + "_tagged.pdf"
    return FileResponse(
        pdf_path,
        media_type="application/pdf",
        filename=pdf_filename,
    )


@app.get("/ocr/job/{job_id}/text")
async def download_text_json(job_id: str):
    """Download the OCR text JSON (plain text per page) for a completed job."""
    job = job_manager.get_job(job_id)
    if job is None:
        raise HTTPException(status_code=404, detail="Job not found")

    if job["status"] != "completed":
        raise HTTPException(status_code=400, detail=f"Job not complete (status: {job['status']})")

    text_json_path = job.get("text_json_path")
    if not text_json_path or not os.path.isfile(text_json_path):
        raise HTTPException(status_code=500, detail="Text JSON not found")

    filename = Path(job["filename"]).stem + "_text.json"
    return FileResponse(
        text_json_path,
        media_type="application/json",
        filename=filename,
    )


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8002)
