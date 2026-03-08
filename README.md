# pdf-native-ocr

Standalone tool that adds native text to scanned/image-only PDFs using DocTR word detection + Qwen VLM text recognition.

Available as a **CLI script** (`run.py`) or a **FastAPI server** (`api.py`).

## How It Works

1. **Render** PDF pages to images (PyMuPDF, DPI=300)
2. **Detect** word bounding boxes (DocTR: db_resnet50 + crnn_vgg16_bn)
3. **Recognize** text via line crops sent to Qwen VLM (optional, improves accuracy)
4. **Convert** to Java-compatible pipeline JSON (coordinate transforms: DPI→PDF points, y-flip)
5. **Tag** PDF with native text layer using Java StandalonePaddleIntegrator

## Project Structure

```
pdf-native-ocr/
├── run.py                      # CLI entry point (config at top)
├── api.py                      # FastAPI server
├── setup.sh                    # Venv creation + dependency install
├── requirements.txt            # Python dependencies
├── ocr/
│   ├── doctr_ocr.py            # DocTR model loading + word bbox extraction
│   ├── qwen_ocr.py             # Qwen full OCR fallback (detection + recognition)
│   ├── qwen_text.py            # Qwen VLM line-crop text recognition
│   └── line_grouping.py        # Group words into lines, crop line regions
├── converter/
│   └── to_pipeline_json.py     # Build Java-compatible JSON from OCR results
├── standalone_java_project/    # Java PDF tagging (Maven project)
│   ├── run_with_json.sh        # Run script: JSON + PDF → tagged PDF
│   ├── pom.xml
│   └── src/
├── input/                      # Place input PDFs here
└── results/                    # Output: JSON + tagged PDFs
```

## Setup

### Prerequisites

- Python 3.11+
- CUDA GPU (for DocTR)
- Java 11+ and Maven (for PDF tagging)
- Qwen VLM API endpoint (vLLM with Qwen2.5-VL, optional)

### Install

```bash
# Auto-setup: creates venv + installs dependencies
./setup.sh

# Or manually:
python3.11 -m venv venv
source venv/bin/activate
pip install -r requirements.txt
```

### Build Java Project

```bash
cd standalone_java_project
mvn -q -DskipTests package
```

## Usage

### CLI Mode

1. Edit the configuration at the top of `run.py`:
   - `INPUT_FILE` — path to your PDF (or `""` to use `INPUT_DIR`)
   - `PAGES` — list of page numbers (empty = all pages)
   - `USE_QWEN_TEXT` — Qwen line-crop text correction
   - `USE_QWEN_FALLBACK` — Qwen full OCR for pages with low word count
   - `QWEN_API_BASE` — your vLLM endpoint URL

2. Run:
```bash
source venv/bin/activate
python run.py
```

3. Output goes to `results/<pdf_name>_<timestamp>/`:
   - `<name>_raw_results.json` — pipeline JSON (with Qwen text)
   - `<name>_doctr_only.json` — pipeline JSON (DocTR only)
   - `<name>_qwen_visible.pdf` — tagged PDF with visible text
   - `<name>_qwen_invisible.pdf` — tagged PDF with invisible text
   - `pages/` — rendered page images + annotated debug images

### FastAPI Server (Async Job Queue)

Start the server:
```bash
./start_api.sh              # default: port 8000
./start_api.sh 8080         # custom port
```

Auto-generated docs at `http://localhost:8000/docs`.

The API uses an **async job queue** — upload a PDF, get a `job_id`, then poll for status and download the tagged PDF when complete. All OCR config is hardcoded in `api.py` (no request parameters needed beyond the PDF file).

#### Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `POST /ocr` | POST | Upload PDF → get `job_id` immediately |
| `GET /ocr/job/{job_id}` | GET | Poll job status + progress |
| `GET /ocr/job/{job_id}/download` | GET | Download tagged PDF (when complete) |
| `GET /health` | GET | Model status + device info |
| `GET /queue/status` | GET | Queue info (pending, running, max) |

#### Workflow

1. **Submit** — `POST /ocr` with the PDF file. Returns `job_id`, `poll_url`, `download_url`.
2. **Poll** — `GET /ocr/job/{job_id}` until `status` is `"completed"` or `"failed"`. The `progress` field shows the current step (e.g. "Running DocTR detection", "Qwen text enhancement").
3. **Download** — `GET /ocr/job/{job_id}/download` returns the invisible-text tagged PDF.

#### Example: Python client

```python
import requests, time

# Step 1: Submit PDF
with open("input.pdf", "rb") as f:
    resp = requests.post("http://localhost:8000/ocr", files={"file": f})
job = resp.json()
job_id = job["job_id"]
print(f"Job submitted: {job_id}")

# Step 2: Poll until complete
while True:
    status = requests.get(f"http://localhost:8000/ocr/job/{job_id}").json()
    print(f"  Status: {status['status']} — {status['progress']}")
    if status["status"] in ("completed", "failed"):
        break
    time.sleep(2)

# Step 3: Download tagged PDF
if status["status"] == "completed":
    resp = requests.get(f"http://localhost:8000/ocr/job/{job_id}/download")
    with open("tagged.pdf", "wb") as out:
        out.write(resp.content)
    print(f"Done: {status['total_words']} words, {status['total_time']}s")
```

#### Example: cURL

```bash
# Submit PDF
curl -X POST http://localhost:8000/ocr -F "file=@input.pdf"
# Returns: {"job_id": "abc-123", "poll_url": "/ocr/job/abc-123", ...}

# Poll status
curl http://localhost:8000/ocr/job/abc-123

# Download tagged PDF
curl http://localhost:8000/ocr/job/abc-123/download -o tagged.pdf
```

#### API Configuration (hardcoded in `api.py`)

All config is set at the top of `api.py` — no request parameters needed:

| Setting | Default | Description |
|---------|---------|-------------|
| `DPI` | `300` | PDF render resolution |
| `DOCTR_DET_ARCH` | `db_resnet50` | DocTR detection model |
| `DOCTR_RECO_ARCH` | `crnn_vgg16_bn` | DocTR recognition model |
| `USE_QWEN_TEXT` | `True` | Qwen line-crop text correction |
| `USE_QWEN_FALLBACK` | `False` | Qwen full OCR for low-word pages |
| `QWEN_FALLBACK_AVG_RATIO` | `0.5` | Fallback threshold (ratio of avg word count) |
| `QWEN_API_BASE` | `http://62.169.159.225/v1` | Qwen vLLM endpoint |
| `QWEN_MODEL` | `Qwen/Qwen2.5-VL-72B-Instruct-AWQ` | Qwen model name |
| `QWEN_MAX_WORKERS` | `15` | Max parallel Qwen API calls |
| `MAX_CONCURRENT_JOBS` | `2` | Max jobs running simultaneously |
| `SAVE_DEBUG` | `True` | Save debug images + page renders |

## CLI Configuration

| Setting | Default | Description |
|---------|---------|-------------|
| `INPUT_FILE` | `""` | Path to input PDF |
| `INPUT_DIR` | `./input` | Process all PDFs in this folder |
| `PAGES` | `[]` (all) | 1-based page numbers to process |
| `DPI` | `300` | Render resolution for DocTR |
| `USE_QWEN_TEXT` | `True` | Enable Qwen line-crop text correction |
| `USE_QWEN_FALLBACK` | `True` | Enable Qwen full OCR for low-word pages |
| `QWEN_FALLBACK_AVG_RATIO` | `0.5` | Pages below this ratio of avg word count trigger fallback |
| `QWEN_API_BASE` | `http://62.169.159.225/v1` | vLLM API endpoint |
| `QWEN_MODEL` | `Qwen/Qwen2.5-VL-72B-Instruct-AWQ` | Model name |
| `QWEN_MAX_WORKERS` | `15` | Parallel Qwen API calls |
| `Y_TOLERANCE` | `15` | Pixel tolerance for line grouping |
| `ZOOM` | `2.0` | Java app coordinate zoom factor |

## Coordinate System

- **DocTR** outputs word bboxes in pixel space at DPI=300 (top-left origin)
- **Pipeline JSON** needs two coordinate systems:
  - `coordinate` — zoom-space image coords: `pixel * (zoom * 72) / DPI`
  - `pdf_bbox` — PDF points (bottom-left origin): `pixel * 72 / DPI`, y-flipped
- The Java app uses `pdf_bbox` to place text in the PDF and `coordinate` for image overlays

## Dependencies

- `python-doctr[torch]` — word-level OCR (detection + recognition)
- `PyMuPDF` — PDF rendering and page info extraction
- `openai` — Qwen VLM API client (OpenAI-compatible)
- `fastapi` — REST API framework
- `uvicorn` — ASGI server
- `python-multipart` — file upload support for FastAPI
- `Pillow` — image handling
- `opencv-python-headless` — required by DocTR
- `numpy` — array operations
