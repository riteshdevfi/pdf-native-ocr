"""
Convert DocTR/Qwen OCR results to Java StandalonePaddleIntegrator-compatible JSON.

No external template needed — generates pdf_page_info from PyMuPDF and
creates "text" elements from OCR word lines.

Expected JSON format for the Java app:
{
  "pages": [
    {
      "page_number": 1,
      "pdf_page_info": {
        "page_number": 1,
        "pdf_width": 612.0,
        "pdf_height": 792.0,
        "zoom": 2.0,
        "image_width": 1224,
        "image_height": 1584
      },
      "results": {
        "res": {
          "boxes": [
            {
              "label": "text",
              "score": 1.0,
              "coordinate": [x1, y1, x2, y2],  // image coords
              "pdf_bbox": [pdf_x1, pdf_y1, pdf_x2, pdf_y2],  // PDF coords (bottom-left origin)
              "words": [
                {"text": "Hello", "bbox": [x1,y1,x2,y2], "pdf_bbox": [px1,py1,px2,py2]}
              ]
            }
          ]
        }
      }
    }
  ]
}
"""

import json
from typing import List, Dict
from pathlib import Path

from ocr.line_grouping import group_words_into_lines, get_line_bbox


def get_pdf_page_info(pdf_path: str, zoom: float = 2.0) -> List[Dict]:
    """
    Extract page dimensions from a PDF using PyMuPDF.
    Returns list of pdf_page_info dicts (one per page).
    """
    import fitz  # PyMuPDF

    doc = fitz.open(pdf_path)
    pages_info = []
    for i, page in enumerate(doc):
        rect = page.rect
        pdf_w = rect.width
        pdf_h = rect.height
        pages_info.append({
            "page_number": i + 1,
            "pdf_width": round(pdf_w, 2),
            "pdf_height": round(pdf_h, 2),
            "zoom": zoom,
            "image_width": int(pdf_w * zoom),
            "image_height": int(pdf_h * zoom),
        })
    doc.close()
    return pages_info


def image_bbox_to_pdf_bbox(bbox: List[int], pdf_page_info: Dict, dpi: int = 300) -> List[float]:
    """
    Convert image pixel bbox (DPI=300, top-left origin) to PDF coordinates (bottom-left origin).

    The DocTR images are rendered at DPI=300.
    The Java app expects PDF coordinates where:
      - x increases left-to-right (same as image)
      - y increases bottom-to-top (OPPOSITE of image)
      - 1 PDF point = 1/72 inch

    Scale factor: DPI=300 means 300px = 1 inch = 72 PDF points
    So: pdf_coord = pixel_coord * (72 / DPI)
    """
    scale = 72.0 / dpi
    pdf_height = pdf_page_info["pdf_height"]

    x_min, y_min, x_max, y_max = bbox

    pdf_x_min = x_min * scale
    pdf_x_max = x_max * scale
    # Y-flip: PDF origin is bottom-left
    pdf_y_min = pdf_height - (y_max * scale)
    pdf_y_max = pdf_height - (y_min * scale)

    return [round(pdf_x_min, 2), round(pdf_y_min, 2), round(pdf_x_max, 2), round(pdf_y_max, 2)]


def scale_bbox_to_zoom(bbox: List[int], dpi: int = 300, zoom: float = 2.0) -> List[int]:
    """
    Scale bbox from DPI=300 image space to zoom=2.0 image space.
    The Java app uses zoom-based image coordinates (zoom * 72 DPI = 144 DPI).
    DocTR uses DPI=300.
    Scale factor: (zoom * 72) / DPI = (2.0 * 72) / 300 = 0.48
    """
    scale = (zoom * 72.0) / dpi
    return [int(bbox[0] * scale), int(bbox[1] * scale), int(bbox[2] * scale), int(bbox[3] * scale)]


def build_pipeline_json(
    pdf_path: str,
    ocr_pages: List[Dict],
    page_numbers: List[int],
    dpi: int = 300,
    zoom: float = 2.0,
    y_tolerance: int = 15,
) -> Dict:
    """
    Build Java-compatible pipeline JSON from OCR results.

    Args:
        pdf_path: Path to the original PDF (for page dimensions)
        ocr_pages: List of page OCR results from doctr_ocr.run_doctr()
                   Each has: image_width, image_height, words: [{text, confidence, bbox}]
        page_numbers: 1-based page numbers corresponding to ocr_pages
        dpi: DPI used when rendering images for DocTR
        zoom: Zoom factor for the Java app coordinate system
        y_tolerance: Pixel tolerance for line grouping
    """
    all_page_info = get_pdf_page_info(pdf_path, zoom=zoom)

    pages = []
    for i, (ocr_page, page_num) in enumerate(zip(ocr_pages, page_numbers)):
        page_info = all_page_info[page_num - 1]
        words = ocr_page["words"]

        # Group words into lines → each line becomes a "text" element
        lines = group_words_into_lines(words, y_tolerance=y_tolerance)

        boxes = []
        for line_words in lines:
            line_bbox = get_line_bbox(line_words)
            line_text = " ".join(w["text"] for w in line_words)

            # Convert line bbox
            coordinate = scale_bbox_to_zoom(line_bbox, dpi=dpi, zoom=zoom)
            pdf_bbox = image_bbox_to_pdf_bbox(line_bbox, page_info, dpi=dpi)

            # Convert each word
            word_list = []
            for w in line_words:
                w_coord = scale_bbox_to_zoom(w["bbox"], dpi=dpi, zoom=zoom)
                w_pdf = image_bbox_to_pdf_bbox(w["bbox"], page_info, dpi=dpi)
                word_list.append({
                    "text": w["text"],
                    "bbox": w_coord,
                    "bbox_2d": w_coord,
                    "pdf_bbox": w_pdf,
                    "source": "doctr_qwen",
                    "angle": w.get("angle", 0),
                })

            boxes.append({
                "label": "text",
                "score": 1.0,
                "coordinate": coordinate,
                "pdf_bbox": pdf_bbox,
                "text": line_text,
                "words": word_list,
            })

        pages.append({
            "page_number": page_num,
            "pdf_page_info": page_info,
            "results": {
                "res": {
                    "boxes": boxes,
                }
            },
        })

    return {
        "metadata": {
            "pipeline_version": "pdf-native-ocr-1.0",
            "layout_detector": "none",
            "ocr_method": "doctr_qwen_hybrid",
        },
        "pages": pages,
    }


def save_pipeline_json(data: Dict, output_path: str):
    """Save pipeline JSON to file."""
    with open(output_path, "w") as f:
        json.dump(data, f, indent=2)
    print(f"  Saved pipeline JSON: {output_path}")
