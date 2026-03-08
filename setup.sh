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
    python3.11 -m venv "$VENV_DIR"
else
    echo "Venv already exists at $VENV_DIR"
fi

# Activate and install
source "$VENV_DIR/bin/activate"
echo "Python: $(python --version)"

echo ""
echo "Installing dependencies..."
pip install --upgrade pip
pip install -r "$SCRIPT_DIR/requirements.txt"

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
