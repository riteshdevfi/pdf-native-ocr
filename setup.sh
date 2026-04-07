#!/bin/bash
# Setup script for pdf-native-ocr
# Creates a Python venv and installs dependencies.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="$SCRIPT_DIR/venv"

echo "=========================================="
echo "pdf-native-ocr Setup"
echo "=========================================="

# Create venv
if [ ! -d "$VENV_DIR" ]; then
    echo "Creating Python venv at $VENV_DIR..."
    python3 -m venv "$VENV_DIR"
else
    echo "Venv already exists at $VENV_DIR"
fi

# Activate and install
source "$VENV_DIR/bin/activate"
echo "Python: $(python --version)"

echo ""
echo "Installing system dependencies..."
sudo apt install -y libgl1 libglib2.0-0 2>/dev/null || echo "  (skipped — install libgl1 manually if cv2 import fails)"

echo ""
echo "Installing Python dependencies..."
pip install --upgrade pip
pip install -r "$SCRIPT_DIR/requirements.txt"

# Install PyTorch with CUDA support (replaces CPU-only version from PyPI)
echo ""
echo "Installing PyTorch with CUDA support..."
pip install torch torchvision --index-url https://download.pytorch.org/whl/cu124

echo ""
echo "=========================================="
echo "Setup complete!"
echo "=========================================="
echo ""
echo "Activate the environment:"
echo "  source $VENV_DIR/bin/activate"
echo ""
echo "Then run:"
echo "  python run.py"
