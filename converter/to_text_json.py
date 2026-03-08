"""
Convert pipeline JSON to a plain-text-per-page JSON response.

Reconstructs document layout from bounding boxes:
- Lines sorted top-to-bottom by Y coordinate
- Words sorted left-to-right within each line
- Paragraph breaks detected from Y gaps between lines
"""

import json
from typing import Dict, List


def pipeline_json_to_text(pipeline_json: Dict) -> Dict:
    """
    Convert pipeline JSON to text-per-page JSON.

    Output format:
    {
      "pages": [
        {"page_number": 1, "text": "line1\nline2\n\nline3..."},
        ...
      ]
    }
    """
    pages_out = []

    for page in pipeline_json.get("pages", []):
        page_num = page["page_number"]
        boxes = page.get("results", {}).get("res", {}).get("boxes", [])

        if not boxes:
            pages_out.append({"page_number": page_num, "text": ""})
            continue

        # Sort lines top-to-bottom by Y1 coordinate
        sorted_boxes = sorted(boxes, key=lambda b: b["coordinate"][1])

        # Average line height → used to detect paragraph gaps
        line_heights = [
            b["coordinate"][3] - b["coordinate"][1]
            for b in sorted_boxes
        ]
        avg_line_height = sum(line_heights) / len(line_heights) if line_heights else 20
        para_gap_threshold = avg_line_height * 1.5

        lines_text = []
        prev_y_bottom = None

        for box in sorted_boxes:
            x1, y1, x2, y2 = box["coordinate"]

            # Insert blank line if gap between this line and previous is large
            if prev_y_bottom is not None and (y1 - prev_y_bottom) > para_gap_threshold:
                lines_text.append("")

            # Sort words left-to-right and join with spaces
            words = sorted(box.get("words", []), key=lambda w: w["bbox"][0])
            line_text = " ".join(
                w["text"] for w in words if w.get("text", "").strip()
            )
            if line_text.strip():
                lines_text.append(line_text)

            prev_y_bottom = y2

        pages_out.append({
            "page_number": page_num,
            "text": "\n".join(lines_text),
        })

    return {"pages": pages_out}


def save_text_json(data: Dict, output_path: str):
    """Save text JSON to file."""
    with open(output_path, "w") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)
