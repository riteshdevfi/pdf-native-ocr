"""Qwen VLM full OCR: text detection + recognition with bounding boxes.

Used as fallback when DocTR detects too few words on a page (e.g. handwritten text).
Sends the full page image to Qwen and asks for word-level bounding boxes + text.
"""

import io
import re
import json
import base64
from typing import List, Dict, Optional, Any
from concurrent.futures import ThreadPoolExecutor, as_completed

from PIL import Image
from openai import OpenAI


def _build_ocr_prompt(width: int, height: int) -> str:
    return f"""Please perform comprehensive OCR on this image and extract ALL text with word-level bounding boxes.
The image dimensions are {width} x {height} pixels.
This includes:
- Printed text
- Handwritten text
- Any text in any language or script

Return the results as a JSON array where each object has:
- "bbox_2d": [x1, y1, x2, y2] - bounding box coordinates in pixels (top-left x, top-left y, bottom-right x, bottom-right y)
- "text_content": "the recognized text"

Important:
- Extract ALL text including handwritten text
- Coordinates MUST be relative to the original image size of {width} x {height} pixels
- x values range from 0 to {width}, y values range from 0 to {height}
- Return coordinates as integers
- Format: [{{"bbox_2d": [x1, y1, x2, y2], "text_content": "text"}}, ...]
- Return ONLY the JSON array, no additional text or explanation.

CRITICAL OUTPUT RULES:
- Output MUST be valid JSON (no trailing commas, no markdown).
- If the full output would be too long, return a SHORTER list but keep it valid JSON."""


def _pil_to_base64(image: Image.Image) -> str:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG", quality=95)
    buffer.seek(0)
    return base64.b64encode(buffer.read()).decode("utf-8")


def _strip_markdown_fences(text: str) -> str:
    if not text:
        return text
    t = text.strip()
    if "```json" in t:
        t = t.split("```json", 1)[1].split("```", 1)[0].strip()
    elif "```" in t:
        t = t.split("```", 1)[1].split("```", 1)[0].strip()
    return t


def _try_parse_json(text: str) -> Optional[Any]:
    try:
        return json.loads(text)
    except Exception:
        return None


def _salvage_json_objects(text: str) -> List[Dict]:
    """Best-effort recovery for truncated/malformed JSON arrays."""
    if not text:
        return []
    t = _strip_markdown_fences(text)
    start = t.find("[")
    if start == -1:
        return []
    t = t[start:]

    decoder = json.JSONDecoder()
    idx = 1  # skip '['
    out = []
    while idx < len(t):
        while idx < len(t) and t[idx] in " \r\n\t,":
            idx += 1
        if idx < len(t) and t[idx] == "]":
            break
        try:
            obj, next_idx = decoder.raw_decode(t, idx)
        except Exception:
            break
        if isinstance(obj, dict):
            out.append(obj)
        idx = next_idx
    return out


def _normalize_ocr_items(items: List[Dict]) -> List[Dict]:
    """Normalize Qwen OCR items to {bbox: [x1,y1,x2,y2], text: str} format."""
    normalized = []
    for it in items or []:
        if not isinstance(it, dict):
            continue
        bbox = it.get("bbox_2d") or it.get("bbox") or it.get("bbox2d")
        text = it.get("text_content") or it.get("text") or it.get("content")
        if bbox is None or text is None:
            continue
        if not isinstance(bbox, list) or len(bbox) < 4:
            continue
        try:
            bbox_int = [int(round(float(b))) for b in bbox[:4]]
        except Exception:
            continue
        txt = str(text).strip()
        if not txt:
            continue
        normalized.append({"bbox": bbox_int, "text": txt, "confidence": 0.8})
    return normalized


def _resize_for_qwen(image: Image.Image, max_dim: int = 1500) -> tuple:
    """Resize image so its longest side is max_dim pixels.
    Returns (resized_image, scale_x, scale_y) where scale factors map
    resized coordinates back to original coordinates."""
    w, h = image.size
    if max(w, h) <= max_dim:
        return image, 1.0, 1.0
    if w >= h:
        new_w = max_dim
        new_h = int(h * max_dim / w)
    else:
        new_h = max_dim
        new_w = int(w * max_dim / h)
    resized = image.resize((new_w, new_h), Image.LANCZOS)
    scale_x = w / new_w
    scale_y = h / new_h
    return resized, scale_x, scale_y


def _qwen_ocr_single(
    image: Image.Image,
    client: OpenAI,
    model_name: str,
    max_tokens: int = 8192,
    max_retries: int = 2,
    y_offset: int = 0,
) -> List[Dict]:
    """Run Qwen OCR on a single image (or image section).
    y_offset is added to all returned bbox y-coordinates (for stitching strips back)."""
    orig_w, orig_h = image.size

    # Resize to max 1500px so Qwen's coordinates match what we send
    send_img, scale_x, scale_y = _resize_for_qwen(image, max_dim=1500)
    send_w, send_h = send_img.size

    b64 = _pil_to_base64(send_img)
    prompt = _build_ocr_prompt(send_w, send_h)

    if scale_x > 1.01:
        print(f"      (resized {orig_w}x{orig_h} → {send_w}x{send_h} for Qwen, scale back {scale_x:.2f}x{scale_y:.2f})")

    response_text = None
    for attempt in range(max_retries):
        try:
            response = client.chat.completions.create(
                model=model_name,
                messages=[{
                    "role": "user",
                    "content": [
                        {"type": "text", "text": prompt},
                        {"type": "image_url", "image_url": {"url": f"data:image/png;base64,{b64}"}},
                    ],
                }],
                max_tokens=max_tokens,
                temperature=0.1,
            )
            response_text = response.choices[0].message.content
            break
        except Exception as e:
            if attempt < max_retries - 1:
                import time
                print(f"      Qwen OCR retry {attempt+1}/{max_retries}: {e}")
                time.sleep(3)
            else:
                print(f"      Qwen OCR failed after {max_retries} attempts: {e}")
                return []

    if not response_text:
        return []

    # Parse JSON response
    cleaned = _strip_markdown_fences(response_text)
    words = None

    parsed = _try_parse_json(cleaned)
    if isinstance(parsed, list):
        words = _normalize_ocr_items(parsed)

    if words is None:
        # Try extracting embedded JSON array
        json_match = re.search(r"\[[\s\S]*\]", cleaned)
        if json_match:
            parsed2 = _try_parse_json(json_match.group())
            if isinstance(parsed2, list):
                words = _normalize_ocr_items(parsed2)

    if words is None:
        # Salvage partial objects
        salvaged = _normalize_ocr_items(_salvage_json_objects(cleaned))
        if salvaged:
            print(f"      Salvaged {len(salvaged)} words from malformed Qwen OCR response")
            words = salvaged

    if not words:
        # Empty array [] is valid (no text in strip), only log error for actual parse failures
        if cleaned.strip() == "[]" or (isinstance(parsed, list) and len(parsed) == 0):
            return []
        print(f"      Qwen OCR: failed to parse response ({len(cleaned)} chars)")
        return []

    # Scale coordinates back from resized image to original DPI=300 space
    if scale_x > 1.01 or scale_y > 1.01:
        for w in words:
            w["bbox"][0] = int(w["bbox"][0] * scale_x)
            w["bbox"][1] = int(w["bbox"][1] * scale_y)
            w["bbox"][2] = int(w["bbox"][2] * scale_x)
            w["bbox"][3] = int(w["bbox"][3] * scale_y)

    # Apply y_offset for stitching strips back together
    if y_offset > 0:
        for w in words:
            w["bbox"][1] += y_offset
            w["bbox"][3] += y_offset

    return words


def _save_strip_debug(strip: Image.Image, words: List[Dict], debug_dir: str,
                      page_num: int, strip_idx: int, y_offset: int):
    """Save strip image, annotated version, and JSON with dimensions + words."""
    import os
    import cv2
    import numpy as np

    os.makedirs(debug_dir, exist_ok=True)

    # Save raw strip
    strip.save(os.path.join(debug_dir, f"page_{page_num}_strip_{strip_idx+1}.png"))

    # Save strip info JSON (dimensions + words for debugging)
    strip_w, strip_h = strip.size
    strip_info = {
        "page_number": page_num,
        "strip_index": strip_idx + 1,
        "strip_dimensions": {"width": strip_w, "height": strip_h},
        "y_offset": y_offset,
        "word_count": len(words),
        "words": words,
    }
    json_path = os.path.join(debug_dir, f"page_{page_num}_strip_{strip_idx+1}.json")
    with open(json_path, "w") as f:
        json.dump(strip_info, f, indent=2)

    # Save annotated strip with bboxes
    img_cv = cv2.cvtColor(np.array(strip), cv2.COLOR_RGB2BGR)
    for w in words:
        # Adjust bbox back to strip-local coordinates for drawing
        x1, y1, x2, y2 = w["bbox"]
        y1_local = y1 - y_offset
        y2_local = y2 - y_offset
        cv2.rectangle(img_cv, (x1, y1_local), (x2, y2_local), (0, 255, 0), 2)
        label = w["text"]
        font_scale = 0.4
        (tw, th), _ = cv2.getTextSize(label, cv2.FONT_HERSHEY_SIMPLEX, font_scale, 1)
        cv2.rectangle(img_cv, (x1, y1_local - th - 4), (x1 + tw, y1_local), (0, 255, 0), -1)
        cv2.putText(img_cv, label, (x1, y1_local - 2),
                    cv2.FONT_HERSHEY_SIMPLEX, font_scale, (0, 0, 0), 1)

    cv2.imwrite(os.path.join(debug_dir, f"page_{page_num}_strip_{strip_idx+1}_annotated.png"), img_cv)


def qwen_full_ocr(
    image: Image.Image,
    client: OpenAI,
    model_name: str,
    max_tokens: int = 8192,
    max_retries: int = 2,
    num_strips: int = 3,
    strip_overlap: int = 50,
    debug_dir: str = None,
    page_num: int = 0,
) -> List[Dict]:
    """Run full Qwen OCR on a page image by splitting into horizontal strips.

    Dense pages (handwritten text) produce too many words for a single Qwen call
    (output gets truncated at max_tokens). Splitting into strips ensures each
    section fits within the token limit.

    Args:
        num_strips: Number of horizontal strips to split the page into
        strip_overlap: Pixel overlap between strips (prevents cutting words at boundaries)
        debug_dir: If set, save strip images and annotated versions here
        page_num: Page number for debug filenames

    Returns list of dicts: [{"bbox": [x1,y1,x2,y2], "text": str, "confidence": float}]
    """
    img_w, img_h = image.size
    strip_height = img_h // num_strips

    # Build strip info
    strips = []
    for s in range(num_strips):
        y_start = max(0, s * strip_height - strip_overlap)
        y_end = min(img_h, (s + 1) * strip_height + strip_overlap) if s < num_strips - 1 else img_h
        strip_img = image.crop((0, y_start, img_w, y_end))
        strips.append((s, strip_img, y_start, y_end))

    # Run all strips in parallel
    results = [None] * num_strips

    def _process_strip(s, strip_img, y_start):
        return _qwen_ocr_single(
            image=strip_img,
            client=client,
            model_name=model_name,
            max_tokens=max_tokens,
            max_retries=max_retries,
            y_offset=y_start,
        )

    with ThreadPoolExecutor(max_workers=num_strips) as pool:
        futures = {}
        for s, strip_img, y_start, y_end in strips:
            future = pool.submit(_process_strip, s, strip_img, y_start)
            futures[future] = (s, strip_img, y_start, y_end)

        for future in as_completed(futures):
            s, strip_img, y_start, y_end = futures[future]
            words = future.result()
            results[s] = words
            print(f"        Strip {s+1}/{num_strips} (y={y_start}-{y_end}): {len(words)} words")

            if debug_dir:
                _save_strip_debug(strip_img, words, debug_dir, page_num, s, y_start)

    # Combine all strips in order
    all_words = []
    for strip_words in results:
        if strip_words:
            all_words.extend(strip_words)

    # Deduplicate words in overlap zones (same text, close bboxes)
    if strip_overlap > 0 and len(all_words) > 1:
        all_words = _deduplicate_words(all_words, iou_threshold=0.3)

    return all_words


def _deduplicate_words(words: List[Dict], iou_threshold: float = 0.3) -> List[Dict]:
    """Remove duplicate words from overlapping strips based on bbox IoU."""
    if not words:
        return words

    keep = []
    for w in words:
        is_dup = False
        for k in keep:
            if w["text"].lower() == k["text"].lower():
                iou = _bbox_iou(w["bbox"], k["bbox"])
                if iou > iou_threshold:
                    is_dup = True
                    break
        if not is_dup:
            keep.append(w)
    return keep


def _bbox_iou(a: List[int], b: List[int]) -> float:
    """Calculate IoU between two bboxes [x1,y1,x2,y2]."""
    x1 = max(a[0], b[0])
    y1 = max(a[1], b[1])
    x2 = min(a[2], b[2])
    y2 = min(a[3], b[3])
    inter = max(0, x2 - x1) * max(0, y2 - y1)
    if inter == 0:
        return 0.0
    area_a = (a[2] - a[0]) * (a[3] - a[1])
    area_b = (b[2] - b[0]) * (b[3] - b[1])
    return inter / (area_a + area_b - inter)
