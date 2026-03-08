#!/bin/bash
# Command to process JSON and generate accessible PDF
# Usage: ./run_with_json.sh [json_file] [source_pdf] [output_pdf]
#
# Default paths (if not provided as arguments):
#   JSON: ../pipeline_output/final_sample_pdf_hybrid_results/final_sample_pdf_raw_results.json
#   PDF:  ../final_sample_pdf.pdf
#   OUT:  output/final_sample_pdf_accessible.pdf

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Set default paths or use provided arguments
JSON_FILE="${1:-../results/pipeline_output/final_sample_pdf_hybrid_results/final_sample_pdf_raw_results.json}"
SOURCE_PDF="${2:-../final_sample_pdf.pdf}"
OUTPUT_PDF="${3:-output/final_sample_pdf_accessible.pdf}"

# Convert relative paths to absolute
JSON_FILE="$(cd "$(dirname "$JSON_FILE")" && pwd)/$(basename "$JSON_FILE")"
SOURCE_PDF="$(cd "$(dirname "$SOURCE_PDF")" && pwd)/$(basename "$SOURCE_PDF")"
OUTPUT_DIR="$(dirname "$OUTPUT_PDF")"
OUTPUT_DIR_ABS="$(cd "$SCRIPT_DIR" && mkdir -p "$OUTPUT_DIR" && cd "$OUTPUT_DIR" && pwd)"
OUTPUT_PDF_ABS="$OUTPUT_DIR_ABS/$(basename "$OUTPUT_PDF")"

echo "=========================================="
echo "PDF Accessibility Tagging"
echo "=========================================="
echo "JSON File: $JSON_FILE"
echo "Source PDF: $SOURCE_PDF"
echo "Output PDF: $OUTPUT_PDF_ABS"
echo "=========================================="
echo ""

# Build the project
echo "Building Maven project..."
mvn -q -DskipTests package

# Run the Java application
echo "Generating accessible PDF..."
java -jar target/standalone-paddle-integration-1.0.0.jar \
    "$JSON_FILE" \
    "$SOURCE_PDF" \
    "$OUTPUT_PDF_ABS"

echo ""
echo "✅ Success! Accessible PDF created at: $OUTPUT_PDF_ABS"

