package com.accessibility.paddle.parsers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Parser for standalone PaddleX JSON results with PDF bbox coordinates.
 * This parser handles the new format from save_paddlex_raw_results_standalone.py
 * which includes both image coordinates and PDF coordinates.
 */
public class StandaloneJsonParser {
    private static final Logger logger = LoggerFactory.getLogger(StandaloneJsonParser.class);
    private final ObjectMapper objectMapper;
    
    public StandaloneJsonParser() {
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * Parse standalone PaddleX JSON results file
     * 
     * @param jsonFile Path to the paddlex_raw_results_standalone.json file
     * @return List of StandalonePageResults
     * @throws IOException if file cannot be read or parsed
     */
    public List<StandalonePageResults> parseResults(String jsonFile) throws IOException {
        logger.info("Parsing standalone PaddleX results from: {}", jsonFile);
        
        File file = new File(jsonFile);
        if (!file.exists()) {
            throw new IOException("JSON file not found: " + jsonFile);
        }
        
        try {
            JsonNode root = objectMapper.readTree(file);
            List<StandalonePageResults> pages = new ArrayList<>();
            
            // Handle both old format (array) and new format (object with "pages" key)
            JsonNode pagesNode;
            if (root.isArray()) {
                // Old format: root is directly an array of pages
                pagesNode = root;
                logger.info("Detected old JSON format (array of pages)");
            } else if (root.isObject() && root.has("pages")) {
                // New format: root is an object with "metadata" and "pages" keys
                pagesNode = root.get("pages");
                JsonNode metadataNode = root.get("metadata");
                if (metadataNode != null && metadataNode.isObject()) {
                    logger.info("Detected new JSON format (object with metadata and pages)");
                    logger.info("Metadata: pipeline_version={}, layout_detector={}, qwen_api.enabled={}", 
                               metadataNode.has("pipeline_version") ? metadataNode.get("pipeline_version").asText() : "N/A",
                               metadataNode.has("layout_detector") ? metadataNode.get("layout_detector").asText() : "N/A",
                               metadataNode.has("qwen_api") && metadataNode.get("qwen_api").has("enabled") 
                                   ? metadataNode.get("qwen_api").get("enabled").asBoolean() : false);
                }
            } else {
                throw new IOException("Invalid JSON format: root must be either an array of pages or an object with 'pages' key");
            }
            
            // Parse pages from the pages array (works for both formats)
            if (pagesNode != null && pagesNode.isArray()) {
                for (JsonNode pageNode : pagesNode) {
                    StandalonePageResults pageResults = parsePageResults(pageNode);
                    pages.add(pageResults);
                    logger.info("Parsed page {} with {} elements", 
                               pageResults.getPageNumber(), 
                               pageResults.getElements().size());
                }
            } else {
                throw new IOException("Invalid JSON format: 'pages' must be an array");
            }
            
            logger.info("Successfully parsed {} pages", pages.size());
            return pages;
        } catch (IOException e) {
            logger.error("Failed to parse JSON file: {}", jsonFile, e);
            throw new IOException("Failed to parse standalone PaddleX results: " + e.getMessage(), e);
        }
    }
    
    /**
     * Parse a single page's results
     */
    private StandalonePageResults parsePageResults(JsonNode pageNode) {
        int pageNumber = pageNode.get("page_number").asInt();
        
        // Parse PDF page info
        JsonNode pdfPageInfoNode = pageNode.get("pdf_page_info");
        PdfPageInfo pdfPageInfo = parsePdfPageInfo(pdfPageInfoNode);
        
        // Parse results
        JsonNode resultsNode = pageNode.get("results");
        JsonNode resNode = resultsNode.get("res");
        JsonNode boxesNode = resNode.get("boxes");
        
        List<StandaloneElement> elements = new ArrayList<>();
        if (boxesNode != null && boxesNode.isArray()) {
            for (JsonNode boxNode : boxesNode) {
                StandaloneElement element = parseElement(boxNode);
                elements.add(element);
            }
        }
        
        return new StandalonePageResults(pageNumber, pdfPageInfo, elements);
    }
    
    /**
     * Parse PDF page info
     */
    private PdfPageInfo parsePdfPageInfo(JsonNode node) {
        if (node == null) {
            return new PdfPageInfo();
        }
        
        PdfPageInfo info = new PdfPageInfo();
        info.setPageNumber(node.get("page_number").asInt());
        info.setPdfWidth(node.get("pdf_width").asDouble());
        info.setPdfHeight(node.get("pdf_height").asDouble());
        info.setZoom(node.get("zoom").asDouble());
        info.setImageWidth(node.get("image_width").asInt());
        info.setImageHeight(node.get("image_height").asInt());
        return info;
    }
    
    /**
     * Parse a single element (box)
     */
    private StandaloneElement parseElement(JsonNode boxNode) {
        StandaloneElement element = new StandaloneElement();
        
        // Parse cls_id with default value if missing
        if (boxNode.has("cls_id")) {
            element.setClsId(boxNode.get("cls_id").asInt());
        } else {
            element.setClsId(0);
        }
        
        // Parse label (required)
        element.setLabel(boxNode.get("label").asText());
        
        // Parse score with default value if missing
        if (boxNode.has("score")) {
            element.setScore(boxNode.get("score").asDouble());
        } else {
            element.setScore(1.0);  // Default score for huridocs
        }
        
        // Parse coordinate with null check
        if (boxNode.has("coordinate")) {
            element.setCoordinate(parseDoubleList(boxNode.get("coordinate")));
        }
        
        // Parse pdf_bbox if present
        if (boxNode.has("pdf_bbox")) {
            element.setPdfBbox(parseDoubleList(boxNode.get("pdf_bbox")));
        }
        
        // Parse OCR text if present
        if (boxNode.has("text")) {
            element.setText(boxNode.get("text").asText());
        }
        
        // Parse word-level OCR if present (image-space bboxes)
        if (boxNode.has("words") && boxNode.get("words").isArray()) {
            List<WordData> words = new ArrayList<>();
            for (JsonNode wordNode : boxNode.get("words")) {
                WordData word = new WordData();
                if (wordNode.has("text")) {
                    word.setText(wordNode.get("text").asText());
                }
                // Prefer pdf_bbox (pre-converted PDF coordinates), fall back to bbox_2d/bbox (image coordinates)
                if (wordNode.has("pdf_bbox") && wordNode.get("pdf_bbox").isArray()) {
                    word.setPdfBbox(parseDoubleList(wordNode.get("pdf_bbox")));
                }
                // Always set bbox for backward compatibility (image coordinates)
                if (wordNode.has("bbox_2d") && wordNode.get("bbox_2d").isArray()) {
                    word.setBbox(parseDoubleList(wordNode.get("bbox_2d")));
                } else if (wordNode.has("bbox") && wordNode.get("bbox").isArray()) {
                    word.setBbox(parseDoubleList(wordNode.get("bbox")));
                }
                // Parse rotation angle (0/90/180/270); default 0 for backward compat
                if (wordNode.has("angle")) {
                    word.setAngle(wordNode.get("angle").asInt(0));
                }
                // Keep only words with both text and at least one bbox
                if (word.getText() != null && !word.getText().trim().isEmpty()
                        && (word.getBbox() != null && word.getBbox().size() >= 4
                            || word.getPdfBbox() != null && word.getPdfBbox().size() >= 4)) {
                    words.add(word);
                }
            }
            element.setWords(words);
        }
        
        // Parse alt_text for figures if present
        if (boxNode.has("alt_text")) {
            element.setAltText(boxNode.get("alt_text").asText());
        }
        
        if (boxNode.has("heading_level")) {
            element.setHeadingLevel(boxNode.get("heading_level").asText());
        }
        if (boxNode.has("heading_font_name")) {
            element.setHeadingFontName(boxNode.get("heading_font_name").asText());
        }
        if (boxNode.has("heading_font_size")) {
            element.setHeadingFontSize(boxNode.get("heading_font_size").asDouble());
        }
        if (boxNode.has("heading_text_similarity")) {
            element.setHeadingTextSimilarity(boxNode.get("heading_text_similarity").asDouble());
        }

        // Parse image_id if present
        if (boxNode.has("image_id")) {
            element.setImageId(boxNode.get("image_id").asText());
        }
        
        // Parse image_path if present
        if (boxNode.has("image_path")) {
            element.setImagePath(boxNode.get("image_path").asText());
        }
        
        // Parse custom data (for tables, formulas, and content/TOC)
        if (boxNode.has("custom")) {
            JsonNode customNode = boxNode.get("custom");
            if (customNode.isObject()) {
                // Table custom data
                if (customNode.has("cells")) {
                    TableCustomData tableData = parseTableCustomData(customNode);
                    element.setTableCustomData(tableData);
                }
                // Content (TOC) custom data
                else if (customNode.has("toci_items")) {
                    TocCustomData tocData = parseTocCustomData(customNode);
                    element.setTocCustomData(tocData);
                }
                // Formula custom data (MathML string)
                else if (customNode.isTextual()) {
                    element.setFormulaMathML(customNode.asText());
                }
            }
        }
        
        // Parse list_group fields
        if (boxNode.has("is_list_group")) {
            element.setListGroup(boxNode.get("is_list_group").asBoolean());
        }
        
        if (boxNode.has("list_type")) {
            element.setListType(boxNode.get("list_type").asText());
        }
        
        if (boxNode.has("item_count")) {
            element.setItemCount(boxNode.get("item_count").asInt());
        }
        
        if (boxNode.has("list_items") && boxNode.get("list_items").isArray()) {
            List<java.util.Map<String, Object>> listItems = new ArrayList<>();
            for (JsonNode itemNode : boxNode.get("list_items")) {
                java.util.Map<String, Object> item = objectMapper.convertValue(itemNode, java.util.Map.class);
                
                // Convert numeric arrays to List<Double> for bullet detection fields
                // Jackson might convert them to List<Integer> which causes casting issues
                if (item.containsKey("bullet_padded_bbox_pdf")) {
                    Object bbox = item.get("bullet_padded_bbox_pdf");
                    if (bbox instanceof java.util.List) {
                        List<Double> doubleList = new ArrayList<>();
                        for (Object val : (java.util.List<?>) bbox) {
                            if (val instanceof Number) {
                                doubleList.add(((Number) val).doubleValue());
                            }
                        }
                        item.put("bullet_padded_bbox_pdf", doubleList);
                    }
                }
                if (item.containsKey("bullet_tight_bbox_pdf")) {
                    Object bbox = item.get("bullet_tight_bbox_pdf");
                    if (bbox instanceof java.util.List) {
                        List<Double> doubleList = new ArrayList<>();
                        for (Object val : (java.util.List<?>) bbox) {
                            if (val instanceof Number) {
                                doubleList.add(((Number) val).doubleValue());
                            }
                        }
                        item.put("bullet_tight_bbox_pdf", doubleList);
                    }
                }
                
                listItems.add(item);
            }
            element.setListItems(listItems);
        }
        
        // Parse hybrid mode fields
        if (boxNode.has("source")) {
            element.setSource(boxNode.get("source").asText());
        }
        
        if (boxNode.has("huridocs_label")) {
            element.setHuridocsLabel(boxNode.get("huridocs_label").asText());
        }
        
        if (boxNode.has("paddlex_label")) {
            element.setPaddlexLabel(boxNode.get("paddlex_label").asText());
        }
        
        if (boxNode.has("huridocs_bbox")) {
            element.setHuridocsBbox(parseDoubleList(boxNode.get("huridocs_bbox")));
        }
        
        if (boxNode.has("paddlex_bbox")) {
            element.setPaddlexBbox(parseDoubleList(boxNode.get("paddlex_bbox")));
        }
        
        if (boxNode.has("iou_score")) {
            element.setIouScore(boxNode.get("iou_score").asDouble());
        }
        
        return element;
    }
    
    /**
     * Parse TOC (Table of Contents) custom data with TOCI items
     */
    private TocCustomData parseTocCustomData(JsonNode customNode) {
        TocCustomData tocData = new TocCustomData();
        
        if (customNode.has("is_wired")) {
            tocData.setWired(customNode.get("is_wired").asBoolean());
        }
        
        if (customNode.has("content_type")) {
            tocData.setContentType(customNode.get("content_type").asText());
        }
        
        if (customNode.has("item_count")) {
            tocData.setItemCount(customNode.get("item_count").asInt());
        }
        
        List<TociItemData> tociItems = new ArrayList<>();
        JsonNode tociItemsNode = customNode.get("toci_items");
        if (tociItemsNode != null && tociItemsNode.isArray()) {
            for (JsonNode tociNode : tociItemsNode) {
                TociItemData tociItem = parseTociItem(tociNode);
                tociItems.add(tociItem);
            }
        }
        tocData.setTociItems(tociItems);
        
        return tocData;
    }
    
    /**
     * Parse a single TOCI item
     */
    private TociItemData parseTociItem(JsonNode tociNode) {
        TociItemData tociItem = new TociItemData();
        
        if (tociNode.has("toci_index")) {
            tociItem.setTociIndex(tociNode.get("toci_index").asInt());
        }
        
        if (tociNode.has("row")) {
            tociItem.setRow(tociNode.get("row").asInt());
        }
        
        if (tociNode.has("column")) {
            tociItem.setColumn(tociNode.get("column").asInt());
        }
        
        if (tociNode.has("coordinate")) {
            tociItem.setCoordinate(parseDoubleList(tociNode.get("coordinate")));
        }
        
        if (tociNode.has("pdf_bbox")) {
            tociItem.setPdfBbox(parseDoubleList(tociNode.get("pdf_bbox")));
        }
        
        if (tociNode.has("box")) {
            tociItem.setBox(parseDoubleList(tociNode.get("box")));
        }
        
        if (tociNode.has("bbox")) {
            tociItem.setBbox(parseDoubleList(tociNode.get("bbox")));
        }
        
        if (tociNode.has("text")) {
            tociItem.setText(tociNode.get("text").asText());
        }
        
        if (tociNode.has("score")) {
            tociItem.setScore(tociNode.get("score").asDouble());
        }
        
        return tociItem;
    }
    
    /**
     * Parse table custom data with cells
     */
    private TableCustomData parseTableCustomData(JsonNode customNode) {
        TableCustomData tableData = new TableCustomData();
        tableData.setRows(customNode.get("rows").asInt());
        tableData.setColumns(customNode.get("columns").asInt());
        
        List<TableCellData> cells = new ArrayList<>();
        JsonNode cellsNode = customNode.get("cells");
        if (cellsNode != null && cellsNode.isArray()) {
            for (JsonNode cellNode : cellsNode) {
                TableCellData cell = parseTableCell(cellNode);
                cells.add(cell);
            }
        }
        tableData.setCells(cells);
        
        return tableData;
    }
    
    /**
     * Parse a single table cell
     */
    private TableCellData parseTableCell(JsonNode cellNode) {
        TableCellData cell = new TableCellData();
        
        cell.setRow(cellNode.get("row").asInt());
        cell.setColumn(cellNode.get("column").asInt());
        cell.setRowSpan(cellNode.get("row_span").asInt());
        cell.setColumnSpan(cellNode.get("column_span").asInt());
        cell.setBox(parseDoubleList(cellNode.get("box")));
        cell.setBbox(parseDoubleList(cellNode.get("bbox")));
        
        // Parse pdf_bbox if present - THIS IS THE KEY FOR PDF COORDINATES!
        if (cellNode.has("pdf_bbox")) {
            cell.setPdfBbox(parseDoubleList(cellNode.get("pdf_bbox")));
        }
        
        // Check if cell is empty
        if (cellNode.has("is_empty")) {
            cell.setEmpty(cellNode.get("is_empty").asBoolean());
        }
        
        // Parse OCR text for the cell if present
        if (cellNode.has("text")) {
            cell.setText(cellNode.get("text").asText());
        }
        
        return cell;
    }
    
    /**
     * Parse a JSON array node into List<Double>
     */
    private List<Double> parseDoubleList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return new ArrayList<>();
        }
        
        List<Double> result = new ArrayList<>();
        for (JsonNode elem : node) {
            result.add(elem.asDouble());
        }
        return result;
    }
    
    // TableStructure methods removed - not needed for standalone approach
    // The standalone approach works directly with TableCellData objects
    
    /**
     * Get all table elements from a page
     */
    public List<StandaloneElement> getTableElements(StandalonePageResults pageResults) {
        List<StandaloneElement> tables = new ArrayList<>();
        for (StandaloneElement element : pageResults.getElements()) {
            if ("table".equals(element.getLabel())) {
                tables.add(element);
            }
        }
        return tables;
    }
    
    /**
     * Container for a single page's results
     */
    public static class StandalonePageResults {
        private int pageNumber;
        private PdfPageInfo pdfPageInfo;
        private List<StandaloneElement> elements;
        
        public StandalonePageResults() {
            this.elements = new ArrayList<>();
        }
        
        public StandalonePageResults(int pageNumber, PdfPageInfo pdfPageInfo, List<StandaloneElement> elements) {
            this.pageNumber = pageNumber;
            this.pdfPageInfo = pdfPageInfo;
            this.elements = elements;
        }
        
        public int getPageNumber() { return pageNumber; }
        public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }
        
        public PdfPageInfo getPdfPageInfo() { return pdfPageInfo; }
        public void setPdfPageInfo(PdfPageInfo pdfPageInfo) { this.pdfPageInfo = pdfPageInfo; }
        
        public List<StandaloneElement> getElements() { return elements; }
        public void setElements(List<StandaloneElement> elements) { this.elements = elements; }
    }
    
    /**
     * PDF page information
     */
    public static class PdfPageInfo {
        private int pageNumber;
        private double pdfWidth;
        private double pdfHeight;
        private double zoom;
        private int imageWidth;
        private int imageHeight;
        
        public int getPageNumber() { return pageNumber; }
        public void setPageNumber(int pageNumber) { this.pageNumber = pageNumber; }
        
        public double getPdfWidth() { return pdfWidth; }
        public void setPdfWidth(double pdfWidth) { this.pdfWidth = pdfWidth; }
        
        public double getPdfHeight() { return pdfHeight; }
        public void setPdfHeight(double pdfHeight) { this.pdfHeight = pdfHeight; }
        
        public double getZoom() { return zoom; }
        public void setZoom(double zoom) { this.zoom = zoom; }
        
        public int getImageWidth() { return imageWidth; }
        public void setImageWidth(int imageWidth) { this.imageWidth = imageWidth; }
        
        public int getImageHeight() { return imageHeight; }
        public void setImageHeight(int imageHeight) { this.imageHeight = imageHeight; }
    }
    
    /**
     * A single detected element (text, table, figure, etc.)
     */
    public static class StandaloneElement {
        private int clsId;
        private String label;
        private double score;
        private List<Double> coordinate;  // Image coordinates
        private List<Double> pdfBbox;     // PDF coordinates (bottom-left origin)
        private TableCustomData tableCustomData;
        private TocCustomData tocCustomData;
        private String formulaMathML;
        private String text;              // OCR extracted text
        private List<WordData> words;     // Word-level OCR (image-space bboxes)
        private String imageId;           // Unique image identifier
        private String imagePath;         // Path to cropped image
        
        // List support fields
        private boolean isListGroup;      // Is this a grouped list?
        private String listType;          // bullet, numbered, letter, roman
        private int itemCount;            // Number of items in group
        private List<java.util.Map<String, Object>> listItems;  // Individual list items
        
        // Hybrid mode fields
        private String source;            // "huridocs", "paddlex", or "hybrid"
        private String huridocsLabel;     // Original Huridocs label
        private String paddlexLabel;      // Original PaddleX label
        private List<Double> huridocsBbox; // Huridocs bounding box
        private List<Double> paddlexBbox;  // PaddleX bounding box
        private double iouScore;          // IOU score between matched boxes
        private String headingLevel;
        private String headingFontName;
        private Double headingFontSize;
        private Double headingTextSimilarity;
        private String altText;  // Alt text for figures/images
        
        public int getClsId() { return clsId; }
        public void setClsId(int clsId) { this.clsId = clsId; }
        
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
        
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
        
        public List<Double> getCoordinate() { return coordinate; }
        public void setCoordinate(List<Double> coordinate) { this.coordinate = coordinate; }
        
        public List<Double> getPdfBbox() { return pdfBbox; }
        public void setPdfBbox(List<Double> pdfBbox) { this.pdfBbox = pdfBbox; }
        
        public TableCustomData getTableCustomData() { return tableCustomData; }
        public void setTableCustomData(TableCustomData tableCustomData) { this.tableCustomData = tableCustomData; }
        
        public TocCustomData getTocCustomData() { return tocCustomData; }
        public void setTocCustomData(TocCustomData tocCustomData) { this.tocCustomData = tocCustomData; }
        
        public String getFormulaMathML() { return formulaMathML; }
        public void setFormulaMathML(String formulaMathML) { this.formulaMathML = formulaMathML; }
        
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public List<WordData> getWords() { return words; }
        public void setWords(List<WordData> words) { this.words = words; }
        
        public String getImageId() { return imageId; }
        public void setImageId(String imageId) { this.imageId = imageId; }
        
        public String getImagePath() { return imagePath; }
        public void setImagePath(String imagePath) { this.imagePath = imagePath; }
        
        // List support getters/setters
        public boolean isListGroup() { return isListGroup; }
        public void setListGroup(boolean listGroup) { isListGroup = listGroup; }
        
        public String getListType() { return listType; }
        public void setListType(String listType) { this.listType = listType; }
        
        public int getItemCount() { return itemCount; }
        public void setItemCount(int itemCount) { this.itemCount = itemCount; }
        
        public List<java.util.Map<String, Object>> getListItems() { return listItems; }
        public void setListItems(List<java.util.Map<String, Object>> listItems) { this.listItems = listItems; }
        
        // Hybrid mode getters/setters
        public String getSource() { return source; }
        public void setSource(String source) { this.source = source; }
        
        public String getHuridocsLabel() { return huridocsLabel; }
        public void setHuridocsLabel(String huridocsLabel) { this.huridocsLabel = huridocsLabel; }
        
        public String getPaddlexLabel() { return paddlexLabel; }
        public void setPaddlexLabel(String paddlexLabel) { this.paddlexLabel = paddlexLabel; }
        
        public List<Double> getHuridocsBbox() { return huridocsBbox; }
        public void setHuridocsBbox(List<Double> huridocsBbox) { this.huridocsBbox = huridocsBbox; }
        
        public List<Double> getPaddlexBbox() { return paddlexBbox; }
        public void setPaddlexBbox(List<Double> paddlexBbox) { this.paddlexBbox = paddlexBbox; }
        
        public double getIouScore() { return iouScore; }
        public void setIouScore(double iouScore) { this.iouScore = iouScore; }

        public String getHeadingLevel() { return headingLevel; }
        public void setHeadingLevel(String headingLevel) { this.headingLevel = headingLevel; }

        public String getHeadingFontName() { return headingFontName; }
        public void setHeadingFontName(String headingFontName) { this.headingFontName = headingFontName; }

        public Double getHeadingFontSize() { return headingFontSize; }
        public void setHeadingFontSize(Double headingFontSize) { this.headingFontSize = headingFontSize; }

        public Double getHeadingTextSimilarity() { return headingTextSimilarity; }
        public void setHeadingTextSimilarity(Double headingTextSimilarity) { this.headingTextSimilarity = headingTextSimilarity; }

        public String getAltText() { return altText; }
        public void setAltText(String altText) { this.altText = altText; }
    }

    /**
     * Word-level OCR data.
     * bbox: IMAGE coordinates [x0,y0,x1,y1] with origin at top-left (if pdfBbox not available)
     * pdfBbox: PDF coordinates [x0,y0,x1,y1] with origin at bottom-left (preferred if available)
     */
    public static class WordData {
        private String text;
        private List<Double> bbox;      // Image coordinates (fallback)
        private List<Double> pdfBbox;   // PDF coordinates (preferred if available)
        private int angle = 0;          // Rotation angle: 0, 90, 180, 270 (degrees CCW image was rotated to detect text)

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public List<Double> getBbox() { return bbox; }
        public void setBbox(List<Double> bbox) { this.bbox = bbox; }

        public List<Double> getPdfBbox() { return pdfBbox; }
        public void setPdfBbox(List<Double> pdfBbox) { this.pdfBbox = pdfBbox; }

        public int getAngle() { return angle; }
        public void setAngle(int angle) { this.angle = angle; }
    }
    
    /**
     * Table custom data (rows, columns, cells)
     */
    public static class TableCustomData {
        private int rows;
        private int columns;
        private List<TableCellData> cells;
        
        public TableCustomData() {
            this.cells = new ArrayList<>();
        }
        
        public int getRows() { return rows; }
        public void setRows(int rows) { this.rows = rows; }
        
        public int getColumns() { return columns; }
        public void setColumns(int columns) { this.columns = columns; }
        
        public List<TableCellData> getCells() { return cells; }
        public void setCells(List<TableCellData> cells) { this.cells = cells; }
    }
    
    /**
     * Individual table cell data
     */
    public static class TableCellData {
        private int row;
        private int column;
        private int rowSpan;
        private int columnSpan;
        private List<Double> box;      // Relative to table image
        private List<Double> bbox;     // Absolute image coordinates
        private List<Double> pdfBbox;  // Absolute PDF coordinates (bottom-left origin)
        private boolean isEmpty;
        private String text;           // OCR text for this cell (optional)
        
        public int getRow() { return row; }
        public void setRow(int row) { this.row = row; }
        
        public int getColumn() { return column; }
        public void setColumn(int column) { this.column = column; }
        
        public int getRowSpan() { return rowSpan; }
        public void setRowSpan(int rowSpan) { this.rowSpan = rowSpan; }
        
        public int getColumnSpan() { return columnSpan; }
        public void setColumnSpan(int columnSpan) { this.columnSpan = columnSpan; }
        
        public List<Double> getBox() { return box; }
        public void setBox(List<Double> box) { this.box = box; }
        
        public List<Double> getBbox() { return bbox; }
        public void setBbox(List<Double> bbox) { this.bbox = bbox; }
        
        public List<Double> getPdfBbox() { return pdfBbox; }
        public void setPdfBbox(List<Double> pdfBbox) { this.pdfBbox = pdfBbox; }
        
        public boolean isEmpty() { return isEmpty; }
        public void setEmpty(boolean empty) { isEmpty = empty; }

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }
    
    /**
     * TOC (Table of Contents) custom data with TOCI items
     */
    public static class TocCustomData {
        private boolean isWired;
        private String contentType;
        private int itemCount;
        private List<TociItemData> tociItems;
        
        public TocCustomData() {
            this.tociItems = new ArrayList<>();
        }
        
        public boolean isWired() { return isWired; }
        public void setWired(boolean wired) { isWired = wired; }
        
        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }
        
        public int getItemCount() { return itemCount; }
        public void setItemCount(int itemCount) { this.itemCount = itemCount; }
        
        public List<TociItemData> getTociItems() { return tociItems; }
        public void setTociItems(List<TociItemData> tociItems) { this.tociItems = tociItems; }
    }
    
    /**
     * Individual TOCI (Table of Contents Item) data
     */
    public static class TociItemData {
        private int tociIndex;
        private int row;
        private int column;
        private List<Double> coordinate;
        private List<Double> pdfBbox;
        private List<Double> box;  // Relative to content
        private List<Double> bbox; // Absolute image coordinates
        private String text;
        private double score;
        
        public int getTociIndex() { return tociIndex; }
        public void setTociIndex(int tociIndex) { this.tociIndex = tociIndex; }
        
        public int getRow() { return row; }
        public void setRow(int row) { this.row = row; }
        
        public int getColumn() { return column; }
        public void setColumn(int column) { this.column = column; }
        
        public List<Double> getCoordinate() { return coordinate; }
        public void setCoordinate(List<Double> coordinate) { this.coordinate = coordinate; }
        
        public List<Double> getPdfBbox() { return pdfBbox; }
        public void setPdfBbox(List<Double> pdfBbox) { this.pdfBbox = pdfBbox; }
        
        public List<Double> getBox() { return box; }
        public void setBox(List<Double> box) { this.box = box; }
        
        public List<Double> getBbox() { return bbox; }
        public void setBbox(List<Double> bbox) { this.bbox = bbox; }
        
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
        
        public double getScore() { return score; }
        public void setScore(double score) { this.score = score; }
    }
}

