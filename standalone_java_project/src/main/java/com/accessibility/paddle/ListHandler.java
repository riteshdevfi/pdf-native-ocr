package com.accessibility.paddle;

import com.accessibility.paddle.parsers.StandaloneJsonParser.*;
import com.itextpdf.kernel.pdf.PdfPage;
import com.itextpdf.kernel.pdf.PdfArray;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.tagging.StandardRoles;
import com.itextpdf.kernel.pdf.tagutils.TagTreePointer;
import com.itextpdf.kernel.pdf.tagutils.TagReference;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.annot.PdfAnnotation;
import com.itextpdf.kernel.pdf.annot.PdfLinkAnnotation;
import com.itextpdf.kernel.pdf.action.PdfAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handler for processing list elements and list groups.
 * Creates proper accessibility structure:
 * <L> (List)
 *   <LI> (List Item)
 *     <LBody> (List Item Body)
 *       <P> (Paragraph)
 */
public class ListHandler {
    private static final Logger logger = LoggerFactory.getLogger(ListHandler.class);
    
    /**
     * Process a list group element (multiple consecutive list items)
     */
    public static void processListGroup(StandaloneElement element, TagTreePointer tagPointer, 
                                       PdfCanvas canvas, PdfPage page) {
        // Check if this is actually a list_group
        if (!"list_group".equalsIgnoreCase(element.getLabel()) && !element.isListGroup()) {
            logger.warn("Element is not a list_group, processing as single list item");
            processSingleListItem(element, tagPointer, canvas, page);
            return;
        }
        
        List<Map<String, Object>> listItems = element.getListItems();
        if (listItems == null || listItems.isEmpty()) {
            logger.warn("List group has no items, skipping");
            return;
        }
        
        logger.info("Creating list structure with {} items", listItems.size());
        
        // Create <L> (List) tag
        tagPointer.addTag(StandardRoles.L);
        String listType = element.getListType() != null ? element.getListType() : "bullet";
        tagPointer.getProperties().setActualText(
            String.format("%s list with %d items", listType, listItems.size())
        );
        
        TagReference listRef = tagPointer.getTagReference();
        canvas.openTag(listRef);
        
        // Process each list item
        for (int i = 0; i < listItems.size(); i++) {
            Map<String, Object> listItem = listItems.get(i);
            processListItemInGroup(listItem, i + 1, tagPointer, canvas, page);
        }
        
        // Close list
        canvas.closeTag();
        tagPointer.moveToParent(); // Back to parent (Sect or Document)
        
        logger.info("✅ Created list with {} items", listItems.size());
    }
    
    /**
     * Process a single list item within a list group
     */
    private static void processListItemInGroup(Map<String, Object> listItem, int itemNumber,
                                               TagTreePointer tagPointer, PdfCanvas canvas, PdfPage page) {
        // Create <LI> (List Item) tag
        tagPointer.addTag(StandardRoles.LI);
        
        String marker = (String) listItem.get("marker");
        String content = (String) listItem.get("content");
        String fullText = (String) listItem.get("full_text");
        
        if (fullText == null) {
            fullText = content != null ? content : "";
        }
        
        tagPointer.getProperties().setActualText(fullText);
        
        TagReference liRef = tagPointer.getTagReference();
        canvas.openTag(liRef);
        
        // Create <LBody> (List Body) tag
        tagPointer.addTag(StandardRoles.LBODY);
        TagReference lbodyRef = tagPointer.getTagReference();
        canvas.openTag(lbodyRef);
        
        // Create <LBL> (List Bullet Label) tag if bullet detection data exists
        List<Double> bulletPaddedBboxPdf = (List<Double>) listItem.get("bullet_padded_bbox_pdf");
        if (bulletPaddedBboxPdf != null && bulletPaddedBboxPdf.size() >= 4) {
            // Create LBL tag for the bullet point marker
            try {
                tagPointer.addTag(StandardRoles.LBL);
            } catch (Exception e) {
                // Fallback to custom role if LBL is not available in this iText version
                logger.warn("StandardRoles.LBL not available, using custom role: {}", e.getMessage());
                tagPointer.addTag("LBL");
            }
            tagPointer.getProperties().setActualText(marker != null ? marker : "•");
            
            TagReference lblRef = tagPointer.getTagReference();
            canvas.openTag(lblRef);
            
            // Create Link annotation for the bullet marker area
            tagPointer.addTag(StandardRoles.LINK);
            TagReference bulletLinkRef = tagPointer.getTagReference();
            canvas.openTag(bulletLinkRef);
            
            // Create rectangle for bullet marker using padded bbox
            Rectangle bulletRect = new Rectangle(
                bulletPaddedBboxPdf.get(0).floatValue(),
                bulletPaddedBboxPdf.get(1).floatValue(),
                bulletPaddedBboxPdf.get(2).floatValue() - bulletPaddedBboxPdf.get(0).floatValue(),
                bulletPaddedBboxPdf.get(3).floatValue() - bulletPaddedBboxPdf.get(1).floatValue()
            );
            
            // Add link annotation for bullet marker
            PdfLinkAnnotation bulletAnnotation = new PdfLinkAnnotation(bulletRect);
            bulletAnnotation.setAction(PdfAction.createURI("#Bullet" + itemNumber));
            bulletAnnotation.setBorder(new PdfArray(new float[]{0, 0, 0})); // No visible border
            bulletAnnotation.setHighlightMode(PdfAnnotation.HIGHLIGHT_INVERT);
            page.addAnnotation(bulletAnnotation);
            
            logger.debug("LBL bullet {} bbox: [{}, {}, {}, {}]", 
                        itemNumber, bulletPaddedBboxPdf.get(0), bulletPaddedBboxPdf.get(1), 
                        bulletPaddedBboxPdf.get(2), bulletPaddedBboxPdf.get(3));
            
            canvas.closeTag(); // Close Link
            tagPointer.moveToParent(); // Back to LBL
            
            canvas.closeTag(); // Close LBL
            tagPointer.moveToParent(); // Back to LBody
        }
        
        // Create <P> (Paragraph) tag for content
        tagPointer.addTag(StandardRoles.P);
        tagPointer.getProperties().setActualText(fullText);
        
        TagReference pRef = tagPointer.getTagReference();
        canvas.openTag(pRef);
        
        // Add clickable link annotation if bbox available
        List<Double> pdfBbox = (List<Double>) listItem.get("pdf_bbox");
        if (pdfBbox != null && pdfBbox.size() >= 4) {
            // Create Link tag for clickable annotation
            tagPointer.addTag(StandardRoles.LINK);
            tagPointer.getProperties().setActualText("List item " + itemNumber);
            
            TagReference linkRef = tagPointer.getTagReference();
            canvas.openTag(linkRef);
            
            // Create clickable rectangle
            Rectangle rect = new Rectangle(
                pdfBbox.get(0).floatValue(),
                pdfBbox.get(1).floatValue(),
                pdfBbox.get(2).floatValue() - pdfBbox.get(0).floatValue(),
                pdfBbox.get(3).floatValue() - pdfBbox.get(1).floatValue()
            );
            
            // Add link annotation (clickable area)
            PdfLinkAnnotation linkAnnotation = new PdfLinkAnnotation(rect);
            linkAnnotation.setAction(PdfAction.createURI("#ListItem" + itemNumber));
            linkAnnotation.setBorder(new PdfArray(new float[]{1, 0, 0})); // 1pt border
            linkAnnotation.setHighlightMode(PdfAnnotation.HIGHLIGHT_INVERT);
            page.addAnnotation(linkAnnotation);
            
            logger.debug("List item {} bbox: [{}, {}, {}, {}]", 
                        itemNumber, pdfBbox.get(0), pdfBbox.get(1), pdfBbox.get(2), pdfBbox.get(3));
            
            canvas.closeTag(); // Close Link
            tagPointer.moveToParent(); // Back to P
        }
        
        // Close tags in reverse order
        canvas.closeTag(); // Close P
        tagPointer.moveToParent(); // Back to LBody
        
        canvas.closeTag(); // Close LBody
        tagPointer.moveToParent(); // Back to LI
        
        canvas.closeTag(); // Close LI
        tagPointer.moveToParent(); // Back to L
        
        logger.debug("✅ Created list item {}: {}", itemNumber, 
                    fullText.substring(0, Math.min(50, fullText.length())));
    }
    
    /**
     * Process a single list item (not part of a group)
     * This creates a standalone <P> tag with list formatting
     */
    public static void processSingleListItem(StandaloneElement element, TagTreePointer tagPointer,
                                            PdfCanvas canvas, PdfPage page) {
        // For single list items not in a group, just tag as paragraph with list marker
        tagPointer.addTag(StandardRoles.P);
        
        String text = element.getText() != null ? element.getText() : "List item";
        tagPointer.getProperties().setActualText(text);
        
        TagReference pRef = tagPointer.getTagReference();
        canvas.openTag(pRef);
        
        // Add clickable link annotation if bbox available
        if (element.getPdfBbox() != null && element.getPdfBbox().size() >= 4) {
            List<Double> bbox = element.getPdfBbox();
            
            // Create Link tag for clickable annotation
            tagPointer.addTag(StandardRoles.LINK);
            tagPointer.getProperties().setActualText(text);
            
            TagReference linkRef = tagPointer.getTagReference();
            canvas.openTag(linkRef);
            
            // Create clickable rectangle
            Rectangle rect = new Rectangle(
                bbox.get(0).floatValue(),
                bbox.get(1).floatValue(),
                bbox.get(2).floatValue() - bbox.get(0).floatValue(),
                bbox.get(3).floatValue() - bbox.get(1).floatValue()
            );
            
            // Add link annotation (clickable area)
            PdfLinkAnnotation linkAnnotation = new PdfLinkAnnotation(rect);
            linkAnnotation.setAction(PdfAction.createURI("#ListItem"));
            linkAnnotation.setBorder(new PdfArray(new float[]{1, 0, 0})); // 1pt border
            linkAnnotation.setHighlightMode(PdfAnnotation.HIGHLIGHT_INVERT);
            page.addAnnotation(linkAnnotation);
            
            logger.debug("List item bbox: [{}, {}, {}, {}]", 
                        bbox.get(0), bbox.get(1), bbox.get(2), bbox.get(3));
            
            canvas.closeTag(); // Close Link
            tagPointer.moveToParent(); // Back to P
        }
        
        canvas.closeTag(); // Close P
        tagPointer.moveToParent(); // Back to parent
        
        logger.info("✅ Created standalone list item: {}", 
                   text.substring(0, Math.min(50, text.length())));
    }
    
    /**
     * Group consecutive list items in a page's elements
     * This is a fallback for when Python post-processing wasn't run
     */
    public static List<StandaloneElement> groupConsecutiveListItems(List<StandaloneElement> elements) {
        List<StandaloneElement> grouped = new ArrayList<>();
        List<StandaloneElement> currentGroup = new ArrayList<>();
        
        for (StandaloneElement element : elements) {
            if ("list".equalsIgnoreCase(element.getLabel())) {
                currentGroup.add(element);
            } else {
                // End of list group
                if (!currentGroup.isEmpty()) {
                    if (currentGroup.size() > 1) {
                        // Create grouped list
                        grouped.add(createListGroupFromItems(currentGroup));
                    } else {
                        // Single item, add as is
                        grouped.add(currentGroup.get(0));
                    }
                    currentGroup.clear();
                }
                grouped.add(element);
            }
        }
        
        // Handle remaining list items
        if (!currentGroup.isEmpty()) {
            if (currentGroup.size() > 1) {
                grouped.add(createListGroupFromItems(currentGroup));
            } else {
                grouped.add(currentGroup.get(0));
            }
        }
        
        return grouped;
    }
    
    /**
     * Create a list_group element from individual list items
     */
    private static StandaloneElement createListGroupFromItems(List<StandaloneElement> items) {
        // This would require extending StandaloneElement to support list_group
        // For now, just return the items as-is
        // TODO: Implement proper list grouping in StandaloneElement
        logger.warn("List grouping not yet implemented, returning individual items");
        return items.get(0); // Placeholder
    }
}

