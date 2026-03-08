#!/bin/bash
# Start the pdf-native-ocr FastAPI server with logging.
#
# Usage:
#   ./start_api.sh              # default: host=0.0.0.0, port=8002
#   ./start_api.sh 8080         # custom port
#   ./start_api.sh 8080 2       # custom port + 2 uvicorn workers

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

PORT="${1:-8002}"
WORKERS="${2:-1}"
LOG_DIR="$SCRIPT_DIR/logs"
mkdir -p "$LOG_DIR"

LOG_FILE="$LOG_DIR/api_$(date +%Y%m%d_%H%M%S).log"

# Deactivate conda if active, then activate project venv
conda deactivate 2>/dev/null
source "$SCRIPT_DIR/venv/bin/activate"

echo "=============================================="
echo "  pdf-native-ocr API"
echo "=============================================="
echo "  Port:    $PORT"
echo "  Workers: $WORKERS"
echo "  Log:     $LOG_FILE"
echo "  Docs:    http://localhost:$PORT/docs"
echo "=============================================="

# Start uvicorn using venv's Python explicitly (avoids conda conflicts)
# --reload watches for file changes and auto-restarts the server
"$SCRIPT_DIR/venv/bin/python" -m uvicorn api:app \
    --host 0.0.0.0 \
    --port "$PORT" \
    --reload \
    --reload-dir "$SCRIPT_DIR" \
    --log-level info \
    2>&1 | tee -a "$LOG_FILE"
