"""Group OCR words into text lines and crop line regions."""

from typing import List, Dict, Tuple
from PIL import Image


def group_words_into_lines(words: List[Dict], y_tolerance: int = 15) -> List[List[Dict]]:
    """
    Group word bboxes into text lines based on vertical midpoint proximity.
    Returns list of lines, each line sorted left-to-right.
    """
    if not words:
        return []

    sorted_words = sorted(words, key=lambda w: (w["bbox"][1] + w["bbox"][3]) / 2)

    lines = []
    current_line = [sorted_words[0]]
    current_mid_y = (sorted_words[0]["bbox"][1] + sorted_words[0]["bbox"][3]) / 2

    for w in sorted_words[1:]:
        mid_y = (w["bbox"][1] + w["bbox"][3]) / 2
        if abs(mid_y - current_mid_y) <= y_tolerance:
            current_line.append(w)
            current_mid_y = sum((ww["bbox"][1] + ww["bbox"][3]) / 2 for ww in current_line) / len(current_line)
        else:
            current_line.sort(key=lambda w: w["bbox"][0])
            lines.append(current_line)
            current_line = [w]
            current_mid_y = mid_y

    current_line.sort(key=lambda w: w["bbox"][0])
    lines.append(current_line)
    return lines


def get_line_bbox(line_words: List[Dict]) -> List[int]:
    """Get bounding box of an entire line [x_min, y_min, x_max, y_max]."""
    x_min = min(w["bbox"][0] for w in line_words)
    y_min = min(w["bbox"][1] for w in line_words)
    x_max = max(w["bbox"][2] for w in line_words)
    y_max = max(w["bbox"][3] for w in line_words)
    return [x_min, y_min, x_max, y_max]


def crop_line_region(image: Image.Image, line_words: List[Dict], padding: int = 5) -> Tuple[Image.Image, List[int]]:
    """Crop the bounding region of a text line from the page image."""
    bbox = get_line_bbox(line_words)
    x_min = max(0, bbox[0] - padding)
    y_min = max(0, bbox[1] - padding)
    x_max = min(image.size[0], bbox[2] + padding)
    y_max = min(image.size[1], bbox[3] + padding)

    crop = image.crop((x_min, y_min, x_max, y_max))
    return crop, [x_min, y_min, x_max, y_max]
