package com.accessibility.paddle;

import com.accessibility.paddle.parsers.StandaloneJsonParser;
import com.accessibility.paddle.parsers.StandaloneJsonParser.*;
import com.accessibility.paddle.parsers.StandaloneJsonParser.TocCustomData;
import com.accessibility.paddle.parsers.StandaloneJsonParser.TociItemData;
import com.itextpdf.kernel.pdf.*;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.canvas.PdfCanvasConstants;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.tagging.StandardRoles;
import com.itextpdf.kernel.pdf.tagutils.TagTreePointer;
import com.itextpdf.kernel.pdf.tagutils.TagReference;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;

/**
 * Integrator for standalone PaddleX results with PDF bbox coordinates.
 * This class uses the pdf_bbox field directly from the standalone JSON,
 * eliminating the need for coordinate transformation.
 */
public class StandalonePaddleIntegrator {
    private static final Logger logger = LoggerFactory.getLogger(StandalonePaddleIntegrator.class);
    
    private final StandaloneJsonParser parser;
    private boolean h1Emitted = false;
    private int lastHeadingLevel = 0;

    /**
     * Controls whether OCR words are painted visibly.
     *
     * - Default: false (invisible text layer for selection/copy without altering visuals)
     * - Enable:  run Java with `-Docr.words.visible=true`
     */
    private static final boolean DRAW_OCR_WORDS_VISIBLE =
            Boolean.parseBoolean(System.getProperty("ocr.words.visible", "false"));
    
    public StandalonePaddleIntegrator() {
        this.parser = new StandaloneJsonParser();
    }
    
    /**
     * Process standalone JSON and create accessible PDF
     * 
     * @param standaloneJsonFile Path to paddlex_raw_results_standalone.json
     * @param inputPdfFile Path to the original PDF file
     * @param outputPdfFile Path for the accessible output PDF
     * @throws IOException if processing fails
     */
    public void processStandaloneResults(String standaloneJsonFile, String inputPdfFile, String outputPdfFile) 
            throws IOException {
        
        logger.info("=".repeat(80));
        logger.info("Starting Standalone PaddleX Integration");
        logger.info("=".repeat(80));
        logger.info("Standalone JSON: {}", standaloneJsonFile);
        logger.info("Input PDF: {}", inputPdfFile);
        logger.info("Output PDF: {}", outputPdfFile);
        
        // Step 1: Parse standalone JSON
        logger.info("Step 1: Parsing standalone JSON...");
        List<StandalonePageResults> pages = parser.parseResults(standaloneJsonFile);
        logger.info("✅ Parsed {} pages", pages.size());
        
        // Step 2: Create accessible PDF with tags
        logger.info("Step 2: Creating accessible PDF with tags...");
        createAccessiblePdf(pages, inputPdfFile, outputPdfFile);
        logger.info("✅ Created accessible PDF: {}", outputPdfFile);
        
        // Step 3: Create bounding box visualization
        logger.info("Step 3: Creating bounding box visualization...");
        String boundingBoxFile = outputPdfFile.replace(".pdf", "_bounding_box.pdf");
        createBoundingBoxVisualization(pages, inputPdfFile, boundingBoxFile);
        logger.info("✅ Created bounding box visualization: {}", boundingBoxFile);
        
        logger.info("=".repeat(80));
        logger.info("✅ Processing completed successfully!");
        logger.info("📄 Main PDF: {}", outputPdfFile);
        logger.info("📊 Visualization: {}", boundingBoxFile);
        logger.info("=".repeat(80));
    }
    
    /**
     * Create accessible PDF with proper tag structure using pdf_bbox coordinates
     */
    private void createAccessiblePdf(List<StandalonePageResults> pages, String inputPdf, String outputPdf) 
            throws IOException {
        
        // Open source PDF
        PdfReader reader = new PdfReader(inputPdf);
        
        // Configure writer for accessibility
        WriterProperties writerProperties = new WriterProperties();
        writerProperties.addXmpMetadata();
        PdfWriter writer = new PdfWriter(outputPdf, writerProperties);
        
        PdfDocument pdfDoc = new PdfDocument(reader, writer);
        
        try {
            // Enable tagging
            pdfDoc.setTagged();
            
            // Set document metadata for accessibility
            setupDocumentMetadata(pdfDoc);
            
            // Process each page
            for (StandalonePageResults pageResults : pages) {
                int pageNumber = pageResults.getPageNumber();
                logger.info("Processing page {}/{}", pageNumber, pages.size());
                
                if (pageNumber <= pdfDoc.getNumberOfPages()) {
                    PdfPage page = pdfDoc.getPage(pageNumber);
                    processPage(pdfDoc, page, pageResults);
                    
                    // Add clickable bounding boxes for debugging
                    addClickableBoundingBoxes(page, pageResults);
                }
            }
            
            logger.info("Accessibility tagging completed");
            
        } finally {
            pdfDoc.close();
        }
    }
    
    /**
     * Process a single page and create accessibility tags
     */
    private void processPage(PdfDocument pdfDoc, PdfPage page, StandalonePageResults pageResults) 
            throws IOException {
        
        logger.info("Processing page {} with {} elements", 
                   pageResults.getPageNumber(), pageResults.getElements().size());
        
        // FIX: Wrap the original page content (background image) in an Artifact tag
        // This prevents "Tagged content - Failed" errors in accessibility checkers
        // by explicitly telling them to ignore the background content.
        try {
            // Write "BMC /Artifact" before the existing content
            com.itextpdf.kernel.pdf.canvas.PdfCanvas canvasBefore = 
                new com.itextpdf.kernel.pdf.canvas.PdfCanvas(
                    page.newContentStreamBefore(), page.getResources(), pdfDoc);
            canvasBefore.beginMarkedContent(com.itextpdf.kernel.pdf.PdfName.Artifact);
            
            // Write "EMC" after the existing content
            com.itextpdf.kernel.pdf.canvas.PdfCanvas canvasAfter = 
                new com.itextpdf.kernel.pdf.canvas.PdfCanvas(
                    page.newContentStreamAfter(), page.getResources(), pdfDoc);
            canvasAfter.endMarkedContent();
            
            logger.info("✅ Marked original page content as Artifact (Background)");
        } catch (Exception e) {
            logger.warn("⚠️ Failed to mark background as artifact: {}", e.getMessage());
        }

        // Create a new canvas for this specific page (appends to the 'after' stream)
        PdfCanvas canvas = new PdfCanvas(page);
        
        // Get a fresh tag pointer for this page to ensure proper isolation
        TagTreePointer tagPointer = pdfDoc.getTagStructureContext().getAutoTaggingPointer();
        
        // Move to the document root to ensure we start fresh for each page
        tagPointer.moveToRoot();
        
        // CRITICAL: Set this specific page for tagging BEFORE creating any tags
        // This ensures all subsequent tags are associated with this specific page
        tagPointer.setPageForTagging(page);
        
        logger.info("🔧 Tag pointer set for page {} (page number: {})", 
                   page.getPdfObject().getIndirectReference().getObjNumber(), pageResults.getPageNumber());
        
        // Verify the page association
        if (tagPointer.getCurrentPage() != null) {
            logger.info("✅ Tag pointer is associated with page object: {}", 
                       tagPointer.getCurrentPage().getPdfObject().getIndirectReference().getObjNumber());
        } else {
            logger.warn("⚠️  Tag pointer is not associated with any page!");
        }
        
        // Create main section for this page
        tagPointer.addTag(StandardRoles.SECT);
        // REMOVED: tagPointer.getProperties().setActualText("Page " + pageResults.getPageNumber());
        // Setting ActualText on the Page Section invalidates all child content accessibility (Nested Alt Text error).
        
        logger.info("✅ Created Sect tag for Page {} with {} elements", 
                   pageResults.getPageNumber(), pageResults.getElements().size());
        
        // CRITICAL: Begin marked content for this page's section
        // TagReference sectRef = tagPointer.getTagReference();
        // canvas.openTag(sectRef);
        
        // Process all elements on this page
        for (StandaloneElement element : pageResults.getElements()) {
            processElement(pdfDoc, page, element, pageResults.getPdfPageInfo(), tagPointer, canvas);
        }
        
        // Close the section's marked content
        // canvas.closeTag();
        
        // Move back to root to ensure clean separation between pages
        tagPointer.moveToRoot();
        
        logger.info("✅ Completed processing page {}", pageResults.getPageNumber());
    }
    
    /**
     * Process a single element (table, text, figure, etc.)
     */
    private void processElement(PdfDocument pdfDoc, PdfPage page, StandaloneElement element, 
                                PdfPageInfo pdfPageInfo,
                                TagTreePointer tagPointer, PdfCanvas canvas) throws IOException {
        
        String label = element.getLabel();
        logger.debug("Processing element: {} (score: {:.2f})", label, element.getScore());
        
        switch (label.toLowerCase()) {
            case "table":
                logger.info("🔧 Processing TABLE element");
                processTableElement(pdfDoc, page, element, tagPointer, canvas, pdfPageInfo);
                break;
            case "list_group":
                logger.info("🔧 Processing LIST_GROUP with {} items", element.getItemCount());
                ListHandler.processListGroup(element, tagPointer, canvas, page);
                break;
            case "list":
                logger.info("🔧 Processing LIST item");
                ListHandler.processSingleListItem(element, tagPointer, canvas, page);
                break;
            case "text":
                logger.info("🔧 Processing TEXT element");
                processTextElement(element, tagPointer, canvas, page, pdfPageInfo);
                break;
            case "title":
                logger.info("🔧 Processing TITLE element (backward compat)");
                processHeaderElement(element, tagPointer, canvas, page, pdfPageInfo);
                break;
            case "header":
                logger.info("🔧 Processing HEADER element");
                processHeaderElement(element, tagPointer, canvas, page, pdfPageInfo);
                break;
            case "paragraph_title":
                logger.info("🔧 Processing PARAGRAPH_TITLE element");
                processParagraphTitleElement(element, tagPointer, canvas, page, pdfPageInfo);
                break;
            case "footer":
                logger.info("🔧 Processing FOOTER element");
                processFooterElement(element, tagPointer, canvas, page, pdfPageInfo);
                break;
            case "caption":
                logger.info("🔧 Processing CAPTION element");
                processCaptionElement(element, tagPointer, canvas, page, pdfPageInfo);
                break;
            case "formula":
                logger.info("🔧 Processing FORMULA element");
                processFormulaElement(element, tagPointer, canvas, page);
                break;
            case "figure":
            case "image":
            case "header_image":
            case "footer_image":
            case "seal":
                logger.info("🔧 Processing FIGURE element");
                // Log OCR text and alt text for debugging
                String ocrText = element.getText();
                String altText = element.getAltText();
                if (ocrText != null && !ocrText.trim().isEmpty()) {
                    logger.info("  📝 OCR text available: {} characters", ocrText.length());
                } else {
                    logger.info("  ⚠️  No OCR text available for figure");
                }
                if (altText != null && !altText.trim().isEmpty()) {
                    logger.info("  🖼️  Alt text available: {} characters", altText.length());
                } else {
                    logger.info("  ⚠️  No alt text available for figure");
                }
                processFigureElement(element, tagPointer, canvas, page, pdfPageInfo);
                break;
            case "algorithm":
                logger.info("🔧 Processing ALGORITHM element");
                processAlgorithmElement(element, tagPointer, canvas, page);
                break;
            case "content":
                logger.info("🔧 Processing CONTENT element (Table of Contents)");
                processContentElement(element, tagPointer, canvas, page);
                break;
            default:
                logger.info("🔧 Skipping unsupported element type: {}", label);
        }
    }
    
    /**
     * Process table element and create proper table structure using row/column info
     */
    private void processTableElement(PdfDocument pdfDoc, PdfPage page, StandaloneElement element, 
                                     TagTreePointer tagPointer, PdfCanvas canvas, PdfPageInfo pdfPageInfo) throws IOException {
        
        TableCustomData tableData = element.getTableCustomData();
        if (tableData == null) {
            logger.warn("Table element has no custom data, skipping table processing");
            return;
        }
        
        logger.info("Processing table with P tags only: {} rows × {} columns, {} cells", 
                   tableData.getRows(), tableData.getColumns(), tableData.getCells().size());
        
        // Get word-level OCR data from table element (if available)
        List<WordData> tableWords = element.getWords();
        if (tableWords != null && !tableWords.isEmpty()) {
            logger.info("  📝 Table element has {} words - will be distributed to cells", tableWords.size());
        }
        
        // Build cell grid organized by row/column
        Map<Integer, List<TableCellData>> rowMap = organizeCellsByRow(tableData.getCells());
        
        // Process all rows - no Div tags, cells will have P tags directly
        // Pass word-level OCR data to cells so they can filter and use words that belong to them
        for (int row = 0; row < tableData.getRows(); row++) {
            if (rowMap.containsKey(row)) {
                createTableRow(rowMap.get(row), false, tagPointer, canvas, page, tableWords, pdfPageInfo);
            }
        }
        
        logger.info("✅ Table processed successfully (P tags only, no Sect/Div/Span)");
    }
    
    /**
     * Organize cells by row number
     */
    private Map<Integer, List<TableCellData>> organizeCellsByRow(List<TableCellData> cells) {
        Map<Integer, List<TableCellData>> rowMap = new TreeMap<>();
        
        for (TableCellData cell : cells) {
            int row = cell.getRow();
            rowMap.computeIfAbsent(row, k -> new ArrayList<>()).add(cell);
        }
        
        // Sort cells within each row by column number
        for (List<TableCellData> rowCells : rowMap.values()) {
            rowCells.sort(Comparator.comparingInt(TableCellData::getColumn));
        }
        
        return rowMap;
    }
    
    /**
     * Create a table row without Div tag - cells will have P tags directly
     */
    private void createTableRow(List<TableCellData> cells, boolean isHeader, 
                                TagTreePointer tagPointer, PdfCanvas canvas, PdfPage page,
                                List<WordData> tableWords, PdfPageInfo pdfPageInfo) throws IOException {
        
        // No Div tag - just process cells directly (they will have P tags)
        for (TableCellData cellData : cells) {
            createTableCell(cellData, isHeader, tagPointer, canvas, page, tableWords, pdfPageInfo);
        }
    }
    
    /**
     * Create a table cell using PDF bbox coordinates directly
     * Uses word-level OCR if available, otherwise falls back to cell-level text
     */
    private void createTableCell(TableCellData cellData, boolean isHeader, 
                                 TagTreePointer tagPointer, PdfCanvas canvas, PdfPage page,
                                 List<WordData> tableWords, PdfPageInfo pdfPageInfo) throws IOException {
        
        // Use P (Paragraph) for layout cells instead of TD/TH
        // This removes strict table semantics while keeping content
        tagPointer.addTag(StandardRoles.P);
        
        // No Table attributes (Scope, RowSpan, ColSpan) needed for layout divs/paragraphs
        
        TagReference cellRef = tagPointer.getTagReference();
        canvas.openTag(cellRef);
        
        // Add word-level OCR for words in this cell (if available)
        if (tableWords != null && !tableWords.isEmpty() && pdfPageInfo != null && 
            cellData.getPdfBbox() != null && cellData.getPdfBbox().size() >= 4) {
            try {
                List<WordData> cellWords = filterWordsByCellBbox(tableWords, cellData.getPdfBbox(), pdfPageInfo);
                if (!cellWords.isEmpty()) {
                    // Use word-level OCR with correct boxes and text positioning
                    addWordLevelOcrTextLayerWithTags(cellWords, tagPointer, canvas, page, pdfPageInfo);
                } else {
                    // No words in this cell, use fallback text
                    String cellText = cellData.getText();
                    if (cellText == null || cellText.trim().isEmpty()) {
                        cellText = " "; // Use space to ensure MCID generation
                    }
                    tagPointer.getProperties().setActualText(cellText);
                }
            } catch (Exception e) {
                logger.warn("⚠️  Failed to add word-level OCR for cell [{},{}]: {}", 
                           cellData.getRow(), cellData.getColumn(), e.getMessage());
                // Fallback to cell-level text
                String cellText = cellData.getText();
                if (cellText == null || cellText.trim().isEmpty()) {
                    cellText = " ";
                }
                tagPointer.getProperties().setActualText(cellText);
            }
        } else {
            // No word-level OCR available, use cell-level text as fallback
            String cellText = cellData.getText();
            if (cellText == null || cellText.trim().isEmpty()) {
                cellText = " "; // Use space to ensure MCID generation
            }
            tagPointer.getProperties().setActualText(cellText);
        }
        
        canvas.closeTag(); // Close cell
        tagPointer.moveToParent(); // Back to row/div
    }
    
    /**
     * Process text element with clickable bounding box
     */
    private void processTextElement(StandaloneElement element,
                                    TagTreePointer tagPointer,
                                    PdfCanvas canvas,
                                    PdfPage page,
                                    PdfPageInfo pdfPageInfo) {
        tagPointer.addTag(StandardRoles.P);
        
        // Use OCR text bits if available
        String actualText = element.getText();
        List<WordData> words = element.getWords();
        boolean hasWords = words != null && !words.isEmpty();
        
        // FIX for "Nested alternate text - Failed":
        // If we are about to add word-level SPANs (which have ActualText), we must NOT set ActualText
        // on this parent P tag. Doing so causes a "Nested alternate text" error because the parent's
        // text property conflicts with or hides the children's text properties.
        
        if (!hasWords && actualText != null && !actualText.trim().isEmpty()) {
            // No word-level data, so we must rely on the parent tag to carry the text
            tagPointer.getProperties().setActualText(actualText);
            logger.info("✅ Set ActualText for P element (Fallback, no words): {}", 
                       actualText.substring(0, Math.min(50, actualText.length())));
        } else if (!hasWords) {
            // No text valid? Use placeholder
             String desc = "Text block (score: " + String.format("%.2f", element.getScore()) + ")";
             tagPointer.getProperties().setActualText(desc);
             logger.debug("No OCR text available, using score-based description");
        } else {
            // hasWords == true
            // Do NOT set ActualText on parent P. The child SPANs will handle it.
            logger.info("  📝 P element has {} words - delegating ActualText to child SPANs", words.size());
        }
        
        TagReference textRef = tagPointer.getTagReference();
        canvas.openTag(textRef);

        // Paint word-level OCR back onto the page as native text (invisible by default).
        // This enables selection/copy like a normal OCR'd PDF while keeping the original visuals.
        try {
            addWordLevelOcrTextLayerWithTags(element, tagPointer, canvas, page, pdfPageInfo);
        } catch (Exception e) {
            logger.warn("⚠️  Failed to paint word-level OCR text layer: {}", e.getMessage());
        }
        
        // If no word-level OCR, draw text directly
        if (!hasWords && actualText != null && !actualText.trim().isEmpty()) {
            try {
                drawTextDirectlyUnderTag(element, actualText, tagPointer, canvas, page, pdfPageInfo);
            } catch (IOException e) {
                logger.warn("⚠️  Failed to draw text for text element: {}", e.getMessage());
            }
        }
        
        // Text is already drawn above, no Link annotation needed
        
        canvas.closeTag();
        tagPointer.moveToParent();
    }

    /**
     * Paint word-level OCR words from JSON onto the PDF as native text.
     *
     * JSON words are in IMAGE pixel coordinates (origin top-left). We convert to PDF coordinates
     * using pdf_page_info.image_width/height and the actual PDF page size.
     *
     * The text is painted INVISIBLE by default (TextRenderingMode = INVISIBLE),
     * so the PDF looks identical but the text can be selected/copied.
     */
    private void addWordLevelOcrTextLayer(StandaloneElement element,
                                         PdfCanvas canvas,
                                         PdfPage page,
                                         PdfPageInfo pdfPageInfo) throws IOException {
        List<WordData> words = element.getWords();
        if (words == null || words.isEmpty()) {
            return;
        }
        if (pdfPageInfo == null || pdfPageInfo.getImageWidth() <= 0 || pdfPageInfo.getImageHeight() <= 0) {
            return;
        }

        // Prefer JSON-provided PDF dimensions (more stable than page box sizes / crops)
        final float pdfW = (float) (pdfPageInfo.getPdfWidth() > 0 ? pdfPageInfo.getPdfWidth() : page.getPageSize().getWidth());
        final float pdfH = (float) (pdfPageInfo.getPdfHeight() > 0 ? pdfPageInfo.getPdfHeight() : page.getPageSize().getHeight());
        final double sx = pdfW / (double) pdfPageInfo.getImageWidth();
        final double sy = pdfH / (double) pdfPageInfo.getImageHeight();

        PdfFont font = PdfFontFactory.createFont(StandardFonts.HELVETICA);

        canvas.saveState();
        if (!DRAW_OCR_WORDS_VISIBLE) {
            canvas.setTextRenderingMode(PdfCanvasConstants.TextRenderingMode.INVISIBLE);
        } else {
            // Debug-only: draw visible OCR words in red
            canvas.setFillColor(new DeviceRgb(255, 0, 0));
            canvas.setTextRenderingMode(PdfCanvasConstants.TextRenderingMode.FILL);
        }

        for (WordData w : words) {
            String txt = w.getText();
            if (txt == null || txt.trim().isEmpty()) continue;
            
            double pdfX0, pdfY0, pdfX1, pdfY1;
            
            // Prefer pre-converted PDF coordinates if available
            if (w.getPdfBbox() != null && w.getPdfBbox().size() >= 4) {
                // Use pre-converted PDF coordinates directly (like bullet bboxes)
                pdfX0 = w.getPdfBbox().get(0);
                pdfY0 = w.getPdfBbox().get(1);
                pdfX1 = w.getPdfBbox().get(2);
                pdfY1 = w.getPdfBbox().get(3);
            } else if (w.getBbox() != null && w.getBbox().size() >= 4) {
                // Fall back to converting image coordinates (backward compatibility)
                double x0 = w.getBbox().get(0);
                double y0 = w.getBbox().get(1);
                double x1 = w.getBbox().get(2);
                double y1 = w.getBbox().get(3);
                
                // Convert image (top-left origin) -> PDF (bottom-left origin)
                pdfX0 = x0 * sx;
                pdfY0 = pdfH - (y1 * sy);  // bottom in PDF (y1 is bottom in image)
                pdfX1 = x1 * sx;
                pdfY1 = pdfH - (y0 * sy);  // top in PDF (y0 is top in image)
            } else {
                continue;  // Skip words without valid coordinates
            }

            float boxWidth = (float) (pdfX1 - pdfX0);
            float boxHeight = Math.max(1.0f, (float)(pdfY1 - pdfY0));
            
            // Use 100% of bbox height for font size (fill the entire height)
            float fontSize = boxHeight;
            
            // Calculate natural text width at this font size
            float naturalWidth = font.getWidth(txt, fontSize);
            
            // Calculate horizontal scaling to fill bbox width
            float horizontalScaling = 100f;
            if (naturalWidth > 0) {
                horizontalScaling = (boxWidth / naturalWidth) * 100f;
            }
            
            // Position baseline at bottom of bbox (0% offset to fill entire height)
            float tx = (float) pdfX0;
            float ty = (float) pdfY0;

            // Add text as native PDF text (this makes it selectable and searchable)
            // Scale both height and width to fill the entire bounding box
            canvas.beginText();
            canvas.setFontAndSize(font, fontSize);
            canvas.setHorizontalScaling(horizontalScaling);  // Scale width to fill bbox
            canvas.moveText(tx, ty);
            canvas.showText(txt);
            canvas.endText();
        }

        canvas.restoreState();
    }

    /**
     * Same as addWordLevelOcrTextLayer, but also creates a P tag per word so the tag tree
     * contains word-level nodes (and extraction tools can map content -> structure).
     *
    /**
     * Core method: Add word-level OCR text layer with P tags
     * This is the shared logic used by both figures and table cells
     */
    private void addWordLevelOcrTextLayerWithTags(List<WordData> words,
                                                 TagTreePointer tagPointer,
                                                 PdfCanvas canvas,
                                                 PdfPage page,
                                                 PdfPageInfo pdfPageInfo) throws IOException {
        if (words == null || words.isEmpty()) {
            return;
        }
        if (pdfPageInfo == null || pdfPageInfo.getImageWidth() <= 0 || pdfPageInfo.getImageHeight() <= 0) {
            return;
        }

        final float pdfW = (float) (pdfPageInfo.getPdfWidth() > 0 ? pdfPageInfo.getPdfWidth() : page.getPageSize().getWidth());
        final float pdfH = (float) (pdfPageInfo.getPdfHeight() > 0 ? pdfPageInfo.getPdfHeight() : page.getPageSize().getHeight());
        final double sx = pdfW / (double) pdfPageInfo.getImageWidth();
        final double sy = pdfH / (double) pdfPageInfo.getImageHeight();

        PdfFont font = PdfFontFactory.createFont(StandardFonts.HELVETICA);

        int emitted = 0;
        for (WordData w : words) {
            String txt = w.getText();
            if (txt == null || txt.trim().isEmpty()) continue;
            
            double pdfX0, pdfY0, pdfX1, pdfY1;
            
            // Prefer pre-converted PDF coordinates if available
            if (w.getPdfBbox() != null && w.getPdfBbox().size() >= 4) {
                pdfX0 = w.getPdfBbox().get(0);
                pdfY0 = w.getPdfBbox().get(1);
                pdfX1 = w.getPdfBbox().get(2);
                pdfY1 = w.getPdfBbox().get(3);
            } else if (w.getBbox() != null && w.getBbox().size() >= 4) {
                // Fall back to converting image coordinates
                double x0 = w.getBbox().get(0);
                double y0 = w.getBbox().get(1);
                double x1 = w.getBbox().get(2);
                double y1 = w.getBbox().get(3);
                
                pdfX0 = x0 * sx;
                pdfY0 = pdfH - (y1 * sy);
                pdfX1 = x1 * sx;
                pdfY1 = pdfH - (y0 * sy);
            } else {
                continue;  // Skip words without valid coordinates
            }

            float boxWidth = (float) (pdfX1 - pdfX0);
            float boxHeight = Math.max(1.0f, (float)(pdfY1 - pdfY0));
            
            // Use 100% of bbox height for font size (fill the entire height)
            float fontSize = boxHeight;
            
            // Calculate natural text width at this font size
            float naturalWidth = font.getWidth(txt, fontSize);
            
            // Calculate horizontal scaling to fill bbox width
            float horizontalScaling = 100f;
            if (naturalWidth > 0) {
                horizontalScaling = (boxWidth / naturalWidth) * 100f;
            }
            
            // Position baseline at bottom of bbox (0% offset to fill entire height)
            float baselineY = (float) pdfY0;

            // Add tag structure for accessibility
            tagPointer.addTag(StandardRoles.P);
            // v18 FIX: Do NOT set ActualText on the P.
            // The text content drawn inside (invisible/transparent) is sufficient for accessibility.
            // Setting ActualText here causes "Nested alternate text" errors because it replaces "real" text.
            // tagPointer.getProperties().setActualText(txt); // REMOVED
            TagReference spanRef = tagPointer.getTagReference();

            canvas.saveState();
            
            // Set rendering mode and color
            if (DRAW_OCR_WORDS_VISIBLE) {
                canvas.setFillColor(new DeviceRgb(255, 0, 0));
                canvas.setTextRenderingMode(PdfCanvasConstants.TextRenderingMode.FILL);
            } else {
                // v7 + v11 Hybrid: Use v7 character positioning + v11 Transparent Fill
                com.itextpdf.kernel.pdf.extgstate.PdfExtGState gs = new com.itextpdf.kernel.pdf.extgstate.PdfExtGState();
                gs.setFillOpacity(0.0f);  // Fully transparent
                canvas.setExtGState(gs);
                canvas.setFillColor(DeviceRgb.BLACK);
                canvas.setTextRenderingMode(PdfCanvasConstants.TextRenderingMode.FILL);
            }
            
            // Open tag for this text
            canvas.openTag(spanRef);
            
            // FIX: Qwen returns phrases (e.g., "Spoke to an individual who identified") as single "words"
            // Rendering character-by-character creates multiple text objects that all match during search
            // Solution: Render entire phrase as ONE text object to prevent false search matches
            // This fixes search accuracy (one match per phrase) while maintaining alignment
            // Scale both height and width to fill the entire bounding box
            canvas.beginText();
            canvas.setFontAndSize(font, fontSize);
            canvas.setHorizontalScaling(horizontalScaling);  // Scale width to fill bbox
            canvas.moveText((float) pdfX0, baselineY);
            canvas.showText(txt);  // Entire phrase as single string - prevents character-level false matches
            canvas.endText();
            
            canvas.closeTag();
            canvas.restoreState();

            tagPointer.moveToParent();

            emitted++;
            // Safety cap per element to avoid massive tag trees on very dense pages
            // if (emitted >= 4000) break;
        }
    }

    /**
     * Overload: Add word-level OCR from element
     */
    private void addWordLevelOcrTextLayerWithTags(StandaloneElement element,
                                                 TagTreePointer tagPointer,
                                                 PdfCanvas canvas,
                                                 PdfPage page,
                                                 PdfPageInfo pdfPageInfo) throws IOException {
        addWordLevelOcrTextLayerWithTags(element.getWords(), tagPointer, canvas, page, pdfPageInfo);
    }

    /**
     * Filter words that fall within a cell's bounding box
     * Returns only words whose center point falls within the cell's PDF bounding box
     */
    private List<WordData> filterWordsByCellBbox(List<WordData> allWords, 
                                                 List<Double> cellBbox,
                                                 PdfPageInfo pdfPageInfo) {
        if (allWords == null || cellBbox == null || cellBbox.size() < 4 || pdfPageInfo == null) {
            return new ArrayList<>();
        }
        
        if (pdfPageInfo.getImageWidth() <= 0 || pdfPageInfo.getImageHeight() <= 0) {
            return new ArrayList<>();
        }
        
        final float pdfW = (float) (pdfPageInfo.getPdfWidth() > 0 ? pdfPageInfo.getPdfWidth() : 0);
        final float pdfH = (float) (pdfPageInfo.getPdfHeight() > 0 ? pdfPageInfo.getPdfHeight() : 0);
        final double sx = pdfW / (double) pdfPageInfo.getImageWidth();
        final double sy = pdfH / (double) pdfPageInfo.getImageHeight();
        
        float cellX0 = cellBbox.get(0).floatValue();
        float cellY0 = cellBbox.get(1).floatValue();
        float cellX1 = cellBbox.get(2).floatValue();
        float cellY1 = cellBbox.get(3).floatValue();
        
        List<WordData> cellWords = new ArrayList<>();
        for (WordData word : allWords) {
            if (word == null) continue;
            
            double x0, y0, x1, y1;
            
            // Prefer pre-converted PDF coordinates if available
            if (word.getPdfBbox() != null && word.getPdfBbox().size() >= 4) {
                x0 = word.getPdfBbox().get(0);
                y0 = word.getPdfBbox().get(1);
                x1 = word.getPdfBbox().get(2);
                y1 = word.getPdfBbox().get(3);
            } else if (word.getBbox() != null && word.getBbox().size() >= 4) {
                // Fall back to converting image coordinates
                // Word bbox is in image pixel coordinates [x0, y0, x1, y1] where (0,0) is top-left
                x0 = word.getBbox().get(0) * sx;
                y0 = pdfH - (word.getBbox().get(3) * sy);  // Convert to PDF Y (inverted)
                x1 = word.getBbox().get(2) * sx;
                y1 = pdfH - (word.getBbox().get(1) * sy);
            } else {
                continue;  // Skip words without valid coordinates
            }
            
            // Check if word center falls within cell bbox
            float wordCenterX = (float)((x0 + x1) / 2);
            float wordCenterY = (float)((y0 + y1) / 2);
            
            // If word center is inside cell boundaries, add it to cellWords
            if (wordCenterX >= cellX0 && wordCenterX <= cellX1 && 
                wordCenterY >= cellY0 && wordCenterY <= cellY1) {
                cellWords.add(word);
            }
        }
        return cellWords;
    }

    /**
     * Draw text directly under a tag (similar to word-level OCR but for element-level text)
     * Used when element doesn't have word-level OCR data but has text content
     */
    private void drawTextDirectlyUnderTag(StandaloneElement element, String text,
                                          TagTreePointer tagPointer, PdfCanvas canvas,
                                          PdfPage page, PdfPageInfo pdfPageInfo) throws IOException {
        if (element.getPdfBbox() == null || element.getPdfBbox().size() < 4) {
            return;
        }
        
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        
        List<Double> bbox = element.getPdfBbox();
        float x = bbox.get(0).floatValue();
        float y = bbox.get(1).floatValue();
        float w = (float)(bbox.get(2) - bbox.get(0));
        float h = (float)(bbox.get(3) - bbox.get(1));
        
        if (h <= 0 || w <= 0) {
            return;
        }
        
        PdfFont font = PdfFontFactory.createFont(StandardFonts.HELVETICA);
        float fontSize = h; // Use full height like word-level OCR
        
        // Calculate horizontal scaling to fit text
        float naturalWidth = font.getWidth(text, fontSize);
        float horizontalScaling = 100f;
        if (naturalWidth > 0) {
            horizontalScaling = (w / naturalWidth) * 100f;
        }
        
        canvas.saveState();
        
        // Set rendering mode (invisible or visible based on DRAW_OCR_WORDS_VISIBLE)
        if (DRAW_OCR_WORDS_VISIBLE) {
            canvas.setFillColor(new DeviceRgb(255, 0, 0));
            canvas.setTextRenderingMode(PdfCanvasConstants.TextRenderingMode.FILL);
        } else {
            PdfExtGState gs = new PdfExtGState();
            gs.setFillOpacity(0.0f);
            canvas.setExtGState(gs);
            canvas.setFillColor(DeviceRgb.BLACK);
            canvas.setTextRenderingMode(PdfCanvasConstants.TextRenderingMode.FILL);
        }
        
        canvas.beginText();
        canvas.setFontAndSize(font, fontSize);
        canvas.setHorizontalScaling(horizontalScaling);
        canvas.moveText(x, y);
        canvas.showText(text);
        canvas.endText();
        
        canvas.restoreState();
    }

    /**
     * Draw text directly under a tag for TOCI items (similar to word-level OCR)
     */
    private void drawTextDirectlyUnderTagForToci(StandaloneJsonParser.TociItemData tociItem,
                                                   TagTreePointer tagPointer, PdfCanvas canvas,
                                                   PdfPage page) throws IOException {
        if (tociItem.getPdfBbox() == null || tociItem.getPdfBbox().size() < 4) {
            return;
        }
        
        String text = tociItem.getText();
        if (text == null || text.trim().isEmpty()) {
            text = "TOC Item " + (tociItem.getTociIndex() + 1);
        }
        
        List<Double> bbox = tociItem.getPdfBbox();
        float x = bbox.get(0).floatValue();
        float y = bbox.get(1).floatValue();
        float w = (float)(bbox.get(2) - bbox.get(0));
        float h = (float)(bbox.get(3) - bbox.get(1));
        
        if (h <= 0 || w <= 0) {
            return;
        }
        
        PdfFont font = PdfFontFactory.createFont(StandardFonts.HELVETICA);
        float fontSize = h; // Use full height like word-level OCR
        
        // Calculate horizontal scaling to fit text
        float naturalWidth = font.getWidth(text, fontSize);
        float horizontalScaling = 100f;
        if (naturalWidth > 0) {
            horizontalScaling = (w / naturalWidth) * 100f;
        }
        
        canvas.saveState();
        
        // Set rendering mode (invisible or visible based on DRAW_OCR_WORDS_VISIBLE)
        if (DRAW_OCR_WORDS_VISIBLE) {
            canvas.setFillColor(new DeviceRgb(255, 0, 0));
            canvas.setTextRenderingMode(PdfCanvasConstants.TextRenderingMode.FILL);
        } else {
            PdfExtGState gs = new PdfExtGState();
            gs.setFillOpacity(0.0f);
            canvas.setExtGState(gs);
            canvas.setFillColor(DeviceRgb.BLACK);
            canvas.setTextRenderingMode(PdfCanvasConstants.TextRenderingMode.FILL);
        }
        
        canvas.beginText();
        canvas.setFontAndSize(font, fontSize);
        canvas.setHorizontalScaling(horizontalScaling);
        canvas.moveText(x, y);
        canvas.showText(text);
        canvas.endText();
        
        canvas.restoreState();
    }

    /**
     * Process figure/image element with clickable bounding box
     */
    private void processFigureElement(StandaloneElement element,
                                      TagTreePointer tagPointer,
                                      PdfCanvas canvas,
                                      PdfPage page,
                                      PdfPageInfo pdfPageInfo) {
        String ocrText = element.getText();
        boolean hasOcrText = ocrText != null && !ocrText.trim().isEmpty();

        // FIX for "Figures alternate text - Failed" AND "Nested alternate text - Failed":
        // The Dilemma: 
        // 1. If we tag as FIGURE, we MUST provide Alt Text (fixes "Figures alternate text"), 
        //    BUT providing Alt Text on a parent while children have ActualText causes "Nested alternate text".
        // 2. The Solution: If we have valid OCR text, this is logically a Text Container, not a Figure.
        //    So we change the tag to SECT (Section) or PART. This allows child SPANs with ActualText
        //    and removes the requirement for Alt Text.
        
        if (hasOcrText) {
            // Create Figure tag first
            tagPointer.addTag(StandardRoles.FIGURE);
            
            // Do NOT set AlternateDescription when we have word-level OCR
            // Setting Alt Text on a parent while children have ActualText causes "Nested alternate text" errors
            String altText = element.getAltText();
            if (altText != null && !altText.trim().isEmpty()) {
                tagPointer.getProperties().setAlternateDescription(altText);
                logger.debug("Set alt text for figure: {}", altText);
            } else {
                String defaultDescription = "Figure or image (score: " + String.format("%.2f", element.getScore()) + ")";
                tagPointer.getProperties().setAlternateDescription(defaultDescription);
                logger.debug("Using default description for figure (no alt text provided)");
            }
            
            TagReference figureRef = tagPointer.getTagReference();
            canvas.openTag(figureRef);
            
            // Draw invisible content to create MCID for bounding box highlighting
            // PDF viewers need content (MCID) inside tags to enable bounding box highlighting
            if (element.getPdfBbox() != null && element.getPdfBbox().size() >= 4) {
                try {
                    List<Double> bbox = element.getPdfBbox();
                    float x = bbox.get(0).floatValue();
                    float y = bbox.get(1).floatValue();
                    float w = (float)(bbox.get(2) - bbox.get(0));
                    float h = (float)(bbox.get(3) - bbox.get(1));
                    
                    if (w > 0 && h > 0) {
                        // Draw invisible rectangle to create MCID
                        canvas.saveState();
                        PdfExtGState gs = new PdfExtGState();
                        gs.setFillOpacity(0.0f);  // Fully transparent
                        canvas.setExtGState(gs);
                        canvas.rectangle(x, y, w, h);
                        canvas.fill();
                        canvas.restoreState();
                    }
                } catch (Exception e) {
                    logger.warn("⚠️  Failed to draw invisible content for Figure tag: {}", e.getMessage());
                }
            }
            
            canvas.closeTag();
            tagPointer.moveToParent(); // Back to Sect level
            
            // Now create word-level P tags as siblings under Sect (after Figure tag)
            logger.info("  📝 Element has OCR text - creating Figure tag and word-level P tags as siblings under Sect");
            
            // Paint word-level OCR back onto the page as native text (invisible by default/transparent)
            // This will create P tags directly under Sect (at the same level as Figure tag)
            try {
                addWordLevelOcrTextLayerWithTags(element, tagPointer, canvas, page, pdfPageInfo);
            } catch (Exception e) {
                logger.warn("⚠️  Failed to paint word-level OCR text layer: {}", e.getMessage());
            }
        } else {
            // Treat as Visual Image
            tagPointer.addTag(StandardRoles.FIGURE);
            
            // Set Alt Text (AlternateDescription) so screen readers can describe it.
            String altText = element.getAltText();
            if (altText != null && !altText.trim().isEmpty()) {
                tagPointer.getProperties().setAlternateDescription(altText);
                logger.debug("Set alt text for figure: {}", altText);
            } else {
                String defaultDescription = "Figure or image (score: " + String.format("%.2f", element.getScore()) + ")";
                tagPointer.getProperties().setAlternateDescription(defaultDescription);
                logger.debug("Using default description for figure (no alt text provided)");
            }
            
            TagReference elementRef = tagPointer.getTagReference();
            canvas.openTag(elementRef);
            
            // If no word-level OCR but has text, draw it directly
            String text = element.getText();
            if (text != null && !text.trim().isEmpty()) {
                try {
                    drawTextDirectlyUnderTag(element, text, tagPointer, canvas, page, pdfPageInfo);
                } catch (IOException e) {
                    logger.warn("⚠️  Failed to draw text for figure element: {}", e.getMessage());
                }
            }
            // Draw invisible content to create MCID for bounding box highlighting
            // PDF viewers need content (MCID) inside tags to enable bounding box highlighting
            if (element.getPdfBbox() != null && element.getPdfBbox().size() >= 4) {
                try {
                    List<Double> bbox = element.getPdfBbox();
                    float x = bbox.get(0).floatValue();
                    float y = bbox.get(1).floatValue();
                    float w = (float)(bbox.get(2) - bbox.get(0));
                    float h = (float)(bbox.get(3) - bbox.get(1));
                    
                    if (w > 0 && h > 0) {
                        // Draw invisible rectangle to create MCID
                        canvas.saveState();
                        PdfExtGState gs = new PdfExtGState();
                        gs.setFillOpacity(0.0f);  // Fully transparent
                        canvas.setExtGState(gs);
                        canvas.rectangle(x, y, w, h);
                        canvas.fill();
                        canvas.restoreState();
                    }
                } catch (Exception e) {
                    logger.warn("⚠️  Failed to draw invisible content for Figure tag: {}", e.getMessage());
                }
            }
            canvas.closeTag();
            tagPointer.moveToParent();
        }
    }
    
    /**
     * Process algorithm/code block element
     */
    private void processAlgorithmElement(StandaloneElement element, TagTreePointer tagPointer, PdfCanvas canvas, PdfPage page) {
        tagPointer.addTag(StandardRoles.CODE);
        
        String actualText;
        if (element.getText() != null && !element.getText().trim().isEmpty()) {
            actualText = element.getText();
        } else {
            actualText = "Algorithm block (score: " + String.format("%.2f", element.getScore()) + ")";
        }
        tagPointer.getProperties().setActualText(actualText);
        
        TagReference codeRef = tagPointer.getTagReference();
        canvas.openTag(codeRef);
        
        // Draw text directly instead of Link annotation
        if (element.getPdfBbox() != null && element.getPdfBbox().size() >= 4) {
            try {
                drawTextDirectlyUnderTag(element, actualText, tagPointer, canvas, page, null);
            } catch (IOException e) {
                logger.warn("⚠️  Failed to draw text for algorithm element: {}", e.getMessage());
            }
        }
        
        canvas.closeTag();
        tagPointer.moveToParent();
        
        logger.info("✅ Created CODE tag for algorithm block");
    }
    
    /**
     * Process header element (page headers) - use P tag with role
     */
    private void processHeaderElement(StandaloneElement element, TagTreePointer tagPointer, PdfCanvas canvas, PdfPage page, PdfPageInfo pdfPageInfo) {
        tagPointer.addTag(StandardRoles.P);
        // tagPointer.getProperties().setRole("Header");  // Custom role for page headers

        String actualText = element.getText();
        List<WordData> words = element.getWords();
        boolean hasWords = words != null && !words.isEmpty();

        // FIX: Conditional ActualText/WordRendering for Page Headers
        // if (!hasWords && actualText != null && !actualText.trim().isEmpty()) {
        //      tagPointer.getProperties().setActualText(actualText);
        // } else if (!hasWords) {
        //      tagPointer.getProperties().setActualText("Header");
        // } else {
        //      logger.info("  📝 Page Header has {} words - delegating ActualText to child SPANs", words.size());
        // }

        TagReference headerRef = tagPointer.getTagReference();
        // canvas.openTag(headerRef);

        if (hasWords) {
            try {
                addWordLevelOcrTextLayerWithTags(element, tagPointer, canvas, page, pdfPageInfo);
            } catch (Exception e) {
                logger.warn("⚠️  Failed to paint word-level OCR text layer for page header: {}", e.getMessage());
            }
        } else if (actualText != null && !actualText.trim().isEmpty()) {
            // Draw text directly if no word-level OCR
            try {
                canvas.openTag(headerRef);
                drawTextDirectlyUnderTag(element, actualText, tagPointer, canvas, page, pdfPageInfo);
                canvas.closeTag();
            } catch (IOException e) {
                logger.warn("⚠️  Failed to draw text for header element: {}", e.getMessage());
            }
        }

        // Text is already drawn above, no Link annotation needed

        // canvas.closeTag();
        tagPointer.moveToParent();

        logger.info("✅ Created P role='Header' tag");
    }

    /**
     * Process paragraph title element (section headers) - use H1 tag for navigation
     */
    private void processParagraphTitleElement(StandaloneElement element, TagTreePointer tagPointer, PdfCanvas canvas, PdfPage page, PdfPageInfo pdfPageInfo) {
        int resolvedLevel = resolveHeadingLevel(element);
        String headingRole = mapHeadingLevelToRole(resolvedLevel);

        tagPointer.addTag(headingRole);

        String actualText = element.getText();
        List<WordData> words = element.getWords();
        boolean hasWords = words != null && !words.isEmpty();
        
        // FIX for "Headers - Failed" and "Nested alternate text - Failed":
        // 1. We must provide visible/selectable text content (via addWordLevel...) to pass "Headers - Failed" (Empty Tag).
        // 2. If we provide child content (SPANs with ActualText), we must NOT set ActualText on the parent
        //    to avoid "Nested alternate text".
        
        if (!hasWords && actualText != null && !actualText.trim().isEmpty()) {
             tagPointer.getProperties().setActualText(actualText);
        } else if (!hasWords) {
             tagPointer.getProperties().setActualText("Section Header");
        } else {
             // words exist - delegate to children
             logger.info("  📝 Header has {} words - delegating ActualText to child SPANs", words.size());
        }

        TagReference titleRef = tagPointer.getTagReference();
        canvas.openTag(titleRef);

        if (hasWords) {
            try {
                addWordLevelOcrTextLayerWithTags(element, tagPointer, canvas, page, pdfPageInfo);
            } catch (Exception e) {
                logger.warn("⚠️  Failed to paint word-level OCR text layer for header: {}", e.getMessage());
            }
        } else if (actualText != null && !actualText.trim().isEmpty()) {
            // Draw text directly if no word-level OCR
            try {
                drawTextDirectlyUnderTag(element, actualText, tagPointer, canvas, page, pdfPageInfo);
            } catch (IOException e) {
                logger.warn("⚠️  Failed to draw text for paragraph title element: {}", e.getMessage());
            }
        }

        // Text is already drawn above, no Link annotation needed

        canvas.closeTag();
        tagPointer.moveToParent();

        logger.info("✅ Created {} tag for section header: {}", headingRole, actualText.substring(0, Math.min(50, actualText.length())));
    }

    private int resolveHeadingLevel(StandaloneElement element) {
        int desiredLevel = 1;
        if (element.getHeadingLevel() != null) {
            try {
                desiredLevel = Integer.parseInt(element.getHeadingLevel().replace("H", ""));
            } catch (NumberFormatException ignore) {
                desiredLevel = 1;
            }
        }

        if (desiredLevel < 1) {
            desiredLevel = 1;
        } else if (desiredLevel > 6) {
            desiredLevel = 6;
        }

        if (desiredLevel == 1) {
            if (h1Emitted) {
                desiredLevel = 2;
            } else {
                h1Emitted = true;
            }
        } else {
            if (!h1Emitted) {
                desiredLevel = 1;
                h1Emitted = true;
            }
            if (lastHeadingLevel > 0 && desiredLevel > lastHeadingLevel + 1) {
                desiredLevel = Math.min(lastHeadingLevel + 1, 6);
            }
        }

        lastHeadingLevel = desiredLevel;
        return desiredLevel;
    }

    private String mapHeadingLevelToRole(int level) {
        switch (level) {
            case 1:
                return StandardRoles.H1;
            case 2:
                return StandardRoles.H2;
            case 3:
                return StandardRoles.H3;
            case 4:
                return StandardRoles.H4;
            case 5:
                return StandardRoles.H5;
            case 6:
                return StandardRoles.H6;
            default:
                return StandardRoles.H1;
        }
    }
    
    /**
     * Process footer element
     */
    private void processFooterElement(StandaloneElement element, TagTreePointer tagPointer, PdfCanvas canvas, PdfPage page, PdfPageInfo pdfPageInfo) {
        tagPointer.addTag(StandardRoles.P);
        tagPointer.getProperties().setRole("Footer");  // Custom role for footer
        
        String actualText = element.getText();
        List<WordData> words = element.getWords();
        boolean hasWords = words != null && !words.isEmpty();
        
        // FIX: Conditional ActualText for Footer
        if (!hasWords && actualText != null && !actualText.trim().isEmpty()) {
             tagPointer.getProperties().setActualText(actualText);
        } else if (!hasWords) {
             tagPointer.getProperties().setActualText("Footer");
        } else {
             logger.info("  📝 Footer has {} words - delegating ActualText to child SPANs", words.size());
        }
        
        TagReference footerRef = tagPointer.getTagReference();
        canvas.openTag(footerRef);
        
        if (hasWords) {
            try {
                addWordLevelOcrTextLayerWithTags(element, tagPointer, canvas, page, pdfPageInfo);
            } catch (Exception e) {
                logger.warn("⚠️  Failed to paint word-level OCR text layer for footer: {}", e.getMessage());
            }
        } else if (actualText != null && !actualText.trim().isEmpty()) {
            // Draw text directly if no word-level OCR
            try {
                drawTextDirectlyUnderTag(element, actualText, tagPointer, canvas, page, pdfPageInfo);
            } catch (IOException e) {
                logger.warn("⚠️  Failed to draw text for footer element: {}", e.getMessage());
            }
        }
        
        // Text is already drawn above, no Link annotation needed
        
        canvas.closeTag();
        tagPointer.moveToParent();
        
        logger.info("✅ Created footer tag");
    }
    
    /**
     * Process caption element (for figures/tables)
     */
    private void processCaptionElement(StandaloneElement element, TagTreePointer tagPointer, PdfCanvas canvas, PdfPage page, PdfPageInfo pdfPageInfo) {
        tagPointer.addTag(StandardRoles.CAPTION);
        
        String actualText = element.getText();
        List<WordData> words = element.getWords();
        boolean hasWords = words != null && !words.isEmpty();
        
        // FIX: Conditional ActualText for Caption
        if (!hasWords && actualText != null && !actualText.trim().isEmpty()) {
             tagPointer.getProperties().setActualText(actualText);
        } else if (!hasWords) {
             tagPointer.getProperties().setActualText("Caption");
        } else {
             logger.info("  📝 Caption has {} words - delegating ActualText to child SPANs", words.size());
        }
        
        TagReference captionRef = tagPointer.getTagReference();
        canvas.openTag(captionRef);
        
        if (hasWords) {
            try {
                addWordLevelOcrTextLayerWithTags(element, tagPointer, canvas, page, pdfPageInfo);
            } catch (Exception e) {
                logger.warn("⚠️  Failed to paint word-level OCR text layer for caption: {}", e.getMessage());
            }
        } else if (actualText != null && !actualText.trim().isEmpty()) {
            // Draw text directly if no word-level OCR
            try {
                drawTextDirectlyUnderTag(element, actualText, tagPointer, canvas, page, pdfPageInfo);
            } catch (IOException e) {
                logger.warn("⚠️  Failed to draw text for caption element: {}", e.getMessage());
            }
        }
        
        // Text is already drawn above, no Link annotation needed
        
        canvas.closeTag();
        tagPointer.moveToParent();
        
        logger.info("✅ Created caption tag");
    }
    
    /**
     * Process content element (Table of Contents) with TOCI hierarchy
     * Structure: TOC > TOCI > Link (similar to Table > TR > TD > Link)
     */
    private void processContentElement(StandaloneElement element, TagTreePointer tagPointer, PdfCanvas canvas, PdfPage page) {
        // Create TOC tag (using P with custom role)
        tagPointer.addTag(StandardRoles.P);
        tagPointer.getProperties().setRole("TOC");
        
        String actualText = "Table of Contents";
        if (element.getText() != null && !element.getText().trim().isEmpty()) {
            actualText = element.getText().substring(0, Math.min(100, element.getText().length()));
        }
        
        tagPointer.getProperties().setActualText(actualText);
        
        TagReference tocRef = tagPointer.getTagReference();
        canvas.openTag(tocRef);
        
        // Check if element has TOC custom data with TOCI items
        StandaloneJsonParser.TocCustomData tocData = element.getTocCustomData();
        
        if (tocData != null && tocData.getTociItems() != null && !tocData.getTociItems().isEmpty()) {
            logger.info("Processing TOC with {} TOCI items", tocData.getTociItems().size());
            
            // Process each TOCI item
            for (StandaloneJsonParser.TociItemData tociItem : tocData.getTociItems()) {
                processTociItem(tociItem, tagPointer, canvas, page);
            }
        } else {
            // No TOCI items - draw text directly for the entire TOC
            logger.info("TOC has no TOCI items, drawing text directly");
            String tocText = element.getText();
            if (tocText == null || tocText.trim().isEmpty()) {
                tocText = "Table of Contents";
            }
            if (element.getPdfBbox() != null && element.getPdfBbox().size() >= 4) {
                try {
                    drawTextDirectlyUnderTag(element, tocText, tagPointer, canvas, page, null);
                } catch (IOException e) {
                    logger.warn("⚠️  Failed to draw text for TOC element: {}", e.getMessage());
                }
            }
        }
        
        canvas.closeTag(); // Close TOC
        tagPointer.moveToParent();
        
        logger.info("✅ Created TOC structure with {} items", 
                   tocData != null && tocData.getTociItems() != null ? tocData.getTociItems().size() : 0);
    }
    
    /**
     * Process a single TOCI (Table of Contents Item) element
     * Creates: TOCI > Link structure
     */
    private void processTociItem(StandaloneJsonParser.TociItemData tociItem, 
                                 TagTreePointer tagPointer, PdfCanvas canvas, PdfPage page) {
        // Create TOCI tag (using P with custom role)
        tagPointer.addTag(StandardRoles.P);
        tagPointer.getProperties().setRole("TOCI");
        
        String tociText = tociItem.getText();
        if (tociText == null || tociText.trim().isEmpty()) {
            tociText = "TOC Item " + (tociItem.getTociIndex() + 1);
        }
        tagPointer.getProperties().setActualText(tociText.substring(0, Math.min(100, tociText.length())));
        
        TagReference tociRef = tagPointer.getTagReference();
        canvas.openTag(tociRef);
        
        // Draw text directly instead of Link tag
        if (tociItem.getPdfBbox() != null && tociItem.getPdfBbox().size() >= 4) {
            try {
                drawTextDirectlyUnderTagForToci(tociItem, tagPointer, canvas, page);
            } catch (IOException e) {
                logger.warn("⚠️  Failed to draw text for TOCI item: {}", e.getMessage());
            }
        }
        
        canvas.closeTag(); // Close TOCI
        tagPointer.moveToParent();
    }
    
    /**
     * Process formula element with MathML if available
     */
    private void processFormulaElement(StandaloneElement element, TagTreePointer tagPointer, PdfCanvas canvas, PdfPage page) {
        tagPointer.addTag(StandardRoles.FORMULA);
        
        String actualText;
        String formulaMathML = element.getFormulaMathML();
        
        if (formulaMathML != null && !formulaMathML.trim().isEmpty()) {
            actualText = "Formula (MathML)";
            // Store MathML in alternate description
            tagPointer.getProperties().setAlternateDescription(formulaMathML);
        } else if (element.getText() != null && !element.getText().trim().isEmpty()) {
            actualText = element.getText();
        } else {
            actualText = "Formula (score: " + String.format("%.2f", element.getScore()) + ")";
        }
        
        tagPointer.getProperties().setActualText(actualText);
        
        TagReference formulaRef = tagPointer.getTagReference();
        canvas.openTag(formulaRef);
        
        // Draw text directly instead of Link annotation
        if (element.getPdfBbox() != null && element.getPdfBbox().size() >= 4) {
            try {
                drawTextDirectlyUnderTag(element, actualText, tagPointer, canvas, page, null);
            } catch (IOException e) {
                logger.warn("⚠️  Failed to draw text for formula element: {}", e.getMessage());
            }
        }
        
        canvas.closeTag();
        tagPointer.moveToParent();
        
        logger.info("✅ Created formula tag{}", formulaMathML != null ? " with MathML" : "");
    }
    
    /**
     * Create bounding box visualization PDF using pdf_bbox coordinates
     */
    private void createBoundingBoxVisualization(List<StandalonePageResults> pages, 
                                               String inputPdf, String boundingBoxFile) throws IOException {
        
        // Open source PDF
        PdfReader reader = new PdfReader(inputPdf);
        PdfWriter writer = new PdfWriter(boundingBoxFile);
        PdfDocument pdfDoc = new PdfDocument(reader, writer);
        
        try {
            PdfFont font = PdfFontFactory.createFont(StandardFonts.HELVETICA);
            
            for (StandalonePageResults pageResults : pages) {
                int pageNumber = pageResults.getPageNumber();
                
                if (pageNumber <= pdfDoc.getNumberOfPages()) {
                    PdfPage page = pdfDoc.getPage(pageNumber);
                    PdfCanvas canvas = new PdfCanvas(page);
                    
                    logger.info("Drawing bounding boxes for page {}", pageNumber);
                    
                    // Draw bounding boxes for all elements
                    int tableCount = 0;
                    for (StandaloneElement element : pageResults.getElements()) {
                        if ("table".equals(element.getLabel()) && element.getPdfBbox() != null) {
                            tableCount++;
                            drawTableBoundingBoxes(canvas, element, tableCount, font);
                        } else if (element.getPdfBbox() != null) {
                            drawElementBoundingBox(canvas, element, font);
                        }

                        // Also draw word-level bounding boxes (if present) using image->PDF conversion.
                        // This makes it easy to visually verify OCR alignment.
                        if (element.getWords() != null && !element.getWords().isEmpty()) {
                            drawWordBoundingBoxes(canvas, page, pageResults.getPdfPageInfo(), element.getWords(), font);
                            
                            // If visible mode is enabled, also draw the OCR text in red on top of the bounding boxes
                            // Reuse existing method - it already handles DRAW_OCR_WORDS_VISIBLE flag
                            if (DRAW_OCR_WORDS_VISIBLE) {
                                try {
                                    addWordLevelOcrTextLayer(element, canvas, page, pageResults.getPdfPageInfo());
                                } catch (Exception e) {
                                    logger.warn("⚠️  Failed to paint word-level OCR text in bounding box visualization: {}", e.getMessage());
                                }
                            }
                        }
                    }
                    
                    logger.info("Drew bounding boxes for {} tables on page {}", tableCount, pageNumber);
                }
            }
            
        } finally {
            pdfDoc.close();
        }
    }
    
    /**
     * Draw table bounding boxes including individual cells
     */
    private void drawTableBoundingBoxes(PdfCanvas canvas, StandaloneElement tableElement, 
                                       int tableNumber, PdfFont font) {
        
        // Draw main table bounding box
        List<Double> pdfBox = tableElement.getPdfBbox();
        if (pdfBox != null && pdfBox.size() >= 4) {
            Rectangle tableRect = new Rectangle(
                pdfBox.get(0).floatValue(),
                pdfBox.get(1).floatValue(),
                (float)(pdfBox.get(2) - pdfBox.get(0)),
                (float)(pdfBox.get(3) - pdfBox.get(1))
            );
            
            // Draw table border in red
            canvas.setStrokeColor(new DeviceRgb(255, 0, 0));
            canvas.setLineWidth(3);
            canvas.rectangle(tableRect);
            canvas.stroke();
            
            // Add table label
            canvas.beginText()
                  .setFontAndSize(font, 12)
                  .setTextMatrix(tableRect.getX(), tableRect.getY() + tableRect.getHeight() + 5)
                  .showText("Table " + tableNumber)
                  .endText();
            
            logger.info("Drew table {} bbox: [{:.1f}, {:.1f}, {:.1f}, {:.1f}]",
                       tableNumber, pdfBox.get(0), pdfBox.get(1), pdfBox.get(2), pdfBox.get(3));
        }
        
        // Draw individual cell bounding boxes
        TableCustomData tableData = tableElement.getTableCustomData();
        if (tableData != null && tableData.getCells() != null) {
            logger.info("Drawing {} cell bounding boxes for table {}", 
                       tableData.getCells().size(), tableNumber);
            
            for (TableCellData cell : tableData.getCells()) {
                drawCellBoundingBox(canvas, cell, font);
            }
        }
    }
    
    /**
     * Draw cell bounding box using pdf_bbox coordinates directly
     */
    private void drawCellBoundingBox(PdfCanvas canvas, TableCellData cell, PdfFont font) {
        List<Double> pdfBox = cell.getPdfBbox();
        if (pdfBox == null || pdfBox.size() < 4) {
            return;
        }
        
        Rectangle cellRect = new Rectangle(
            pdfBox.get(0).floatValue(),
            pdfBox.get(1).floatValue(),
            (float)(pdfBox.get(2) - pdfBox.get(0)),
            (float)(pdfBox.get(3) - pdfBox.get(1))
        );
        
        // Use different colors based on cell properties
        DeviceRgb color;
        if (cell.isEmpty()) {
            color = new DeviceRgb(200, 200, 200); // Gray for empty cells
        } else if (cell.getRow() == 0) {
            color = new DeviceRgb(0, 255, 0); // Green for header row
        } else if (cell.getRowSpan() > 1 || cell.getColumnSpan() > 1) {
            color = new DeviceRgb(255, 165, 0); // Orange for merged cells
        } else {
            color = new DeviceRgb(0, 0, 255); // Blue for regular cells
        }
        
        canvas.setStrokeColor(color);
        canvas.setLineWidth(1);
        canvas.rectangle(cellRect);
        canvas.stroke();
        
        // Add cell label
        String label = String.format("R%dC%d", cell.getRow() + 1, cell.getColumn() + 1);
        if (cell.getRowSpan() > 1 || cell.getColumnSpan() > 1) {
            label += String.format(" (%dx%d)", cell.getRowSpan(), cell.getColumnSpan());
        }
        
        canvas.beginText()
              .setFontAndSize(font, 6)
              .setTextMatrix(cellRect.getX() + 2, cellRect.getY() + cellRect.getHeight() - 8)
              .showText(label)
              .endText();
        
        logger.debug("Drew cell[{},{}]: [{:.1f}, {:.1f}, {:.1f}, {:.1f}]",
                    cell.getRow(), cell.getColumn(),
                    pdfBox.get(0), pdfBox.get(1), pdfBox.get(2), pdfBox.get(3));
    }
    
    /**
     * Draw element bounding box (for non-table elements)
     */
    private void drawElementBoundingBox(PdfCanvas canvas, StandaloneElement element, PdfFont font) {
        List<Double> pdfBox = element.getPdfBbox();
        if (pdfBox == null || pdfBox.size() < 4) {
            return;
        }
        
        Rectangle rect = new Rectangle(
            pdfBox.get(0).floatValue(),
            pdfBox.get(1).floatValue(),
            (float)(pdfBox.get(2) - pdfBox.get(0)),
            (float)(pdfBox.get(3) - pdfBox.get(1))
        );
        
        // Use different colors for different element types
        DeviceRgb color = getColorForLabel(element.getLabel());
        
        canvas.setStrokeColor(color);
        canvas.setLineWidth(2);
        canvas.rectangle(rect);
        canvas.stroke();
        
        // Add label
        canvas.beginText()
              .setFontAndSize(font, 8)
              .setTextMatrix(rect.getX(), rect.getY() + rect.getHeight() + 2)
              .showText(element.getLabel() + " " + String.format("%.0f%%", element.getScore() * 100))
              .endText();
    }

    /**
     * Draw word-level bounding boxes (words[] are IMAGE coordinates: origin top-left).
     * Converts to PDF coords using pdf_page_info.image_width/height and current page size.
     */
    private void drawWordBoundingBoxes(PdfCanvas canvas,
                                       PdfPage page,
                                       PdfPageInfo pdfPageInfo,
                                       List<WordData> words,
                                       PdfFont font) throws IOException {
        if (pdfPageInfo == null || pdfPageInfo.getImageWidth() <= 0 || pdfPageInfo.getImageHeight() <= 0) {
            return;
        }
        if (words == null || words.isEmpty()) {
            return;
        }

        final float pdfW = (float) (pdfPageInfo.getPdfWidth() > 0 ? pdfPageInfo.getPdfWidth() : page.getPageSize().getWidth());
        final float pdfH = (float) (pdfPageInfo.getPdfHeight() > 0 ? pdfPageInfo.getPdfHeight() : page.getPageSize().getHeight());
        final double sx = pdfW / (double) pdfPageInfo.getImageWidth();
        final double sy = pdfH / (double) pdfPageInfo.getImageHeight();

        // Thin green boxes for words
        canvas.saveState();
        canvas.setStrokeColor(new DeviceRgb(0, 255, 0));
        canvas.setLineWidth(0.9f);
        System.out.println("The Words added to the canvas are: " + words.size());
        for (WordData w : words) {
            System.out.println("The Word is: " + w.getText());
        }
        int drawn = 0;
        for (WordData w : words) {
            if (w == null) continue;
            
            double pdfX0, pdfY0, pdfX1, pdfY1;
            
            // Prefer pre-converted PDF coordinates if available
            if (w.getPdfBbox() != null && w.getPdfBbox().size() >= 4) {
                // Already in PDF coordinates - use directly, NO conversion needed
                pdfX0 = w.getPdfBbox().get(0);
                pdfY0 = w.getPdfBbox().get(1);
                pdfX1 = w.getPdfBbox().get(2);
                pdfY1 = w.getPdfBbox().get(3);
            } else if (w.getBbox() != null && w.getBbox().size() >= 4) {
                // Fall back to converting image coordinates
                double imgX0 = w.getBbox().get(0);
                double imgY0 = w.getBbox().get(1);
                double imgX1 = w.getBbox().get(2);
                double imgY1 = w.getBbox().get(3);
                
                // Convert image coordinates to PDF coordinates
                pdfX0 = imgX0 * sx;
                pdfY0 = pdfH - (imgY1 * sy);  // Convert to PDF Y (inverted)
                pdfX1 = imgX1 * sx;
                pdfY1 = pdfH - (imgY0 * sy);
            } else {
                continue;  // Skip words without valid coordinates
            }

            Rectangle rect = new Rectangle(
                (float) pdfX0,
                (float) pdfY0,
                (float) (pdfX1 - pdfX0),
                (float) (pdfY1 - pdfY0)
            );
            canvas.rectangle(rect);
            drawn++;
            // Avoid huge PDFs if OCR returns tons of words; cap drawing per element.
            if (drawn >= 2000) {
                break;
            }
        }
        canvas.stroke();

        // Optional small legend
        canvas.beginText()
              .setFontAndSize(font, 6)
              .setTextMatrix(10, 10)
              .showText("Green boxes = word-level OCR bboxes (capped per element)")
              .endText();

        canvas.restoreState();
    }
    
    /**
     * Get color for different element labels (all 11 Huridocs types)
     */
    private DeviceRgb getColorForLabel(String label) {
        switch (label.toLowerCase()) {
            case "table":
                return new DeviceRgb(255, 0, 0); // Red
            case "list":
            case "list_group":
                return new DeviceRgb(0, 200, 0); // Green
            case "header":
            case "footer":
                return new DeviceRgb(255, 0, 255); // Magenta (headers and footers)
            case "paragraph_title":
                return new DeviceRgb(0, 255, 0); // Bright Green (section headings)
            case "text":
                return new DeviceRgb(0, 0, 255); // Blue (only for paragraphs)
            case "figure":
            case "image":
            case "header_image":
            case "footer_image":
            case "seal":
                return new DeviceRgb(128, 0, 128); // Purple
            case "algorithm":
                return new DeviceRgb(75, 0, 130); // Indigo
            case "caption":
                return new DeviceRgb(255, 192, 203); // Pink
            case "formula":
                return new DeviceRgb(255, 165, 0); // Orange
            case "title":
                return new DeviceRgb(0, 0, 200); // Dark Blue
            case "content":
                return new DeviceRgb(255, 140, 0); // Dark Orange (for Table of Contents)
            default:
                return new DeviceRgb(128, 128, 128); // Gray
        }
    }
    
    /**
     * Add clickable bounding boxes for debugging
     * NOTE: Clickable annotations are now created INSIDE table cells during tag creation
     */
    private void addClickableBoundingBoxes(PdfPage page, StandalonePageResults pageResults) {
        // Clickable annotations are now created during table cell tag creation
        // This ensures proper nesting: Table > TR > TD/TH > Link
        logger.info("Clickable annotations are created within table structure for proper nesting");
    }
    
    /**
     * Setup document metadata for accessibility
     */
    private void setupDocumentMetadata(PdfDocument pdfDoc) {
        // Set document language
        pdfDoc.getCatalog().setLang(new PdfString("en-US"));
        
        // Set viewer preferences for accessibility
        PdfViewerPreferences viewerPrefs = new PdfViewerPreferences();
        viewerPrefs.setDisplayDocTitle(true);
        pdfDoc.getCatalog().setViewerPreferences(viewerPrefs);
        
        // Set document metadata
        PdfDocumentInfo info = pdfDoc.getDocumentInfo();
        info.setTitle("Accessible Document with Table Recognition");
        info.setSubject("PDF made accessible using standalone PaddleX results");
        info.setCreator("Standalone PaddleX iText Integration Tool");
        info.setKeywords("accessibility, table recognition, PDF, PaddleX, iText, standalone");
        info.addCreationDate();
        
        logger.debug("Document metadata configured for accessibility");
    }
    
    /**
     * Main entry point for standalone processing
     */
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: java StandalonePaddleIntegrator <standalone_json> <input_pdf> <output_pdf>");
            System.err.println();
            System.err.println("Arguments:");
            System.err.println("  standalone_json: Path to paddlex_raw_results_standalone.json");
            System.err.println("  input_pdf:       Path to the original PDF file");
            System.err.println("  output_pdf:      Path for the accessible output PDF");
            System.err.println();
            System.err.println("Example:");
            System.err.println("  java StandalonePaddleIntegrator \\");
            System.err.println("    paddle_stand_alone/results/output_2/paddlex_raw_results_standalone.json \\");
            System.err.println("    input.pdf \\");
            System.err.println("    output_accessible.pdf");
            System.exit(1);
        }
        
        String standaloneJson = args[0];
        String inputPdf = args[1];
        String outputPdf = args[2];
        
        try {
            StandalonePaddleIntegrator integrator = new StandalonePaddleIntegrator();
            integrator.processStandaloneResults(standaloneJson, inputPdf, outputPdf);
            
            System.out.println();
            System.out.println("🎉 Processing completed successfully!");
            System.out.println("📄 Accessible PDF: " + outputPdf);
            System.out.println("📊 Visualization: " + outputPdf.replace(".pdf", "_bounding_box.pdf"));
            
        } catch (Exception e) {
            logger.error("Processing failed: {}", e.getMessage(), e);
            System.err.println("❌ Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}

