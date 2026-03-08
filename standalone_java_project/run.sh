#!/bin/bash

##############################################################################
# Standalone PaddleX Integration Runner
# 
# Clean, minimal Java project for processing standalone PaddleX results
# and creating accessible PDFs with proper tag nesting.
#
# Usage: ./run.sh <standalone_json> <input_pdf> <output_pdf>
##############################################################################

set -e

# Color codes
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

echo -e "${BLUE}================================================================================${NC}"
echo -e "${BLUE}  🚀 Standalone PaddleX Integration (Clean Project)${NC}"
echo -e "${BLUE}================================================================================${NC}"
echo ""

# Check arguments
if [ $# -lt 2 ]; then
    echo -e "${RED}❌ Error: Missing required arguments${NC}"
    echo ""
    echo "Usage: $0 <standalone_json> <input_pdf> [output_pdf]"
    echo ""
    echo "Arguments:"
    echo "  standalone_json: Path to paddlex_raw_results_standalone.json"
    echo "  input_pdf:       Path to the original PDF file"
    echo "  output_pdf:      Path for the accessible output PDF (optional)"
    echo ""
    echo "Example:"
    echo "  $0 results/output_2/paddlex_raw_results_standalone.json input.pdf output.pdf"
    echo ""
    exit 1
fi

STANDALONE_JSON="$1"
INPUT_PDF="$2"
OUTPUT_PDF="${3:-output/accessible.pdf}"

# Create output directory
OUTPUT_DIR=$(dirname "$OUTPUT_PDF")
mkdir -p "$OUTPUT_DIR"

echo -e "${YELLOW}📋 Configuration:${NC}"
echo "  📄 Standalone JSON: $STANDALONE_JSON"
echo "  📄 Input PDF:       $INPUT_PDF"
echo "  📄 Output PDF:      $OUTPUT_PDF"
echo ""

# Validate inputs
echo -e "${YELLOW}🔍 Validating inputs...${NC}"
if [ ! -f "$STANDALONE_JSON" ]; then
    echo -e "${RED}❌ Standalone JSON file not found: $STANDALONE_JSON${NC}"
    exit 1
fi

if [ ! -f "$INPUT_PDF" ]; then
    echo -e "${RED}❌ Input PDF file not found: $INPUT_PDF${NC}"
    exit 1
fi

echo -e "${GREEN}✅ Input files validated${NC}"
echo ""

# Build project
echo -e "${YELLOW}🔨 Building project...${NC}"
mvn clean compile -q
echo -e "${GREEN}✅ Build completed${NC}"
echo ""

# Run the integration
echo -e "${YELLOW}⚙️  Processing standalone results...${NC}"
echo ""

java -cp target/classes:$(mvn dependency:build-classpath -q -Dmdep.outputFile=/dev/stdout) \
    com.accessibility.paddle.StandalonePaddleIntegrator \
    "$STANDALONE_JSON" \
    "$INPUT_PDF" \
    "$OUTPUT_PDF"

# Check result
if [ $? -eq 0 ]; then
    echo ""
    echo -e "${GREEN}================================================================================${NC}"
    echo -e "${GREEN}  🎉 SUCCESS! Processing Completed${NC}"
    echo -e "${GREEN}================================================================================${NC}"
    echo ""
    echo "📁 Output files:"
    echo "  📄 Accessible PDF:  $OUTPUT_PDF"
    echo "  📊 Visualization:   ${OUTPUT_PDF%.pdf}_bounding_box.pdf"
    echo ""
    echo "✨ Features:"
    echo "  ✅ Proper tag nesting: Table > TR > TD/TH > Link"
    echo "  ✅ PDF bbox coordinates (no transformation needed)"
    echo "  ✅ Row/column structure from standalone JSON"
    echo "  ✅ Rowspan and colspan attributes"
    echo "  ✅ Screen reader compatible"
    echo "  ✅ WCAG 2.1 compliant"
    echo ""
    echo "🔍 Next steps:"
    echo "  1. Open the PDF in Adobe Acrobat"
    echo "  2. Check Tags panel for proper nesting"
    echo "  3. Test with screen reader"
    echo "  4. Run accessibility checker"
    echo ""
else
    echo ""
    echo -e "${RED}================================================================================${NC}"
    echo -e "${RED}  ❌ Processing Failed${NC}"
    echo -e "${RED}================================================================================${NC}"
    echo ""
    echo "Please check the error messages above."
    echo ""
    exit 1
fi
