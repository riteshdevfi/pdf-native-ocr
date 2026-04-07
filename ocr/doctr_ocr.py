"""DocTR word-level bounding box detection."""

import time
import numpy as np
from typing import List, Dict, Tuple
from PIL import Image


# ---------------------------------------------------------------------------
# Helpers for multi-orientation support
# ---------------------------------------------------------------------------

def _rotate_image(image: Image.Image, angle: int) -> Image.Image:
    """Rotate image CCW by angle degrees (0, 90, 180, 270)."""
    if angle == 0:
        return image
    elif angle == 90:
        return image.transpose(Image.ROTATE_90)
    elif angle == 180:
        return image.transpose(Image.ROTATE_180)
    elif angle == 270:
        return image.transpose(Image.ROTATE_270)
    raise ValueError(f"Unsupported angle: {angle}")


def _unproject_bbox(bbox: list, angle: int, orig_w: int, orig_h: int) -> list:
    """
    Unproject bbox from rotated image coords back to original image coords.

    Rotation convention: PIL ROTATE_90 = 90° CCW.
      angle=90  → rotated(rx,ry) came from original(orig_w-1-ry, rx)
      angle=180 → rotated(rx,ry) came from original(orig_w-1-rx, orig_h-1-ry)
      angle=270 → rotated(rx,ry) came from original(ry, orig_h-1-rx)
    """
    x1, y1, x2, y2 = bbox
    if angle == 0:
        return [x1, y1, x2, y2]
    elif angle == 90:
        return [orig_w - 1 - y2, x1, orig_w - 1 - y1, x2]
    elif angle == 180:
        return [orig_w - 1 - x2, orig_h - 1 - y2, orig_w - 1 - x1, orig_h - 1 - y1]
    elif angle == 270:
        return [y1, orig_h - 1 - x2, y2, orig_h - 1 - x1]
    raise ValueError(f"Unsupported angle: {angle}")


def _compute_iou(a: list, b: list) -> float:
    """Compute IoU between two [x1,y1,x2,y2] bboxes."""
    ix1 = max(a[0], b[0])
    iy1 = max(a[1], b[1])
    ix2 = min(a[2], b[2])
    iy2 = min(a[3], b[3])
    inter = max(0, ix2 - ix1) * max(0, iy2 - iy1)
    if inter == 0:
        return 0.0
    area_a = (a[2] - a[0]) * (a[3] - a[1])
    area_b = (b[2] - b[0]) * (b[3] - b[1])
    return inter / (area_a + area_b - inter)


def _nms_words(words: list, iou_threshold: float = 0.3) -> list:
    """
    Greedy NMS over word detections in original image space.
    For overlapping words (IoU > threshold), keep highest confidence.
    """
    if not words:
        return []
    words = sorted(words, key=lambda w: w["confidence"], reverse=True)
    suppressed = [False] * len(words)
    kept = []
    for i, w in enumerate(words):
        if suppressed[i]:
            continue
        kept.append(w)
        for j in range(i + 1, len(words)):
            if not suppressed[j] and _compute_iou(w["bbox"], words[j]["bbox"]) > iou_threshold:
                suppressed[j] = True
    return kept


def load_doctr_model(det_arch: str = "db_resnet50", reco_arch: str = "crnn_vgg16_bn", device: str = "auto"):
    """Load DocTR OCR predictor. Returns (predictor, device_str)."""
    import torch
    from doctr.models import ocr_predictor

    if device == "auto":
        device = "cuda" if torch.cuda.is_available() else "cpu"

    predictor = ocr_predictor(det_arch=det_arch, reco_arch=reco_arch, pretrained=True)
    if device == "cuda":
        predictor = predictor.cuda()
        print(f"  DocTR loaded on {torch.cuda.get_device_name(0)}")
    else:
        print(f"  DocTR loaded on CPU")

    return predictor, device


def run_doctr(predictor, images: List[Image.Image], batch_size: int = 32) -> Tuple[list, float]:
    """
    Run DocTR on a list of PIL images in batches.

    Returns:
        (pages_words, elapsed_seconds)
        pages_words: list of dicts per page, each with:
            image_width, image_height, words: [{text, confidence, bbox: [x1,y1,x2,y2]}]
    """
    start = time.perf_counter()
    all_pages = []

    for batch_start in range(0, len(images), batch_size):
        batch_imgs = images[batch_start:batch_start + batch_size]
        np_images = [np.array(img.convert("RGB")) for img in batch_imgs]
        result = predictor(np_images)

        for page_idx, page in enumerate(result.pages):
            img_w, img_h = batch_imgs[page_idx].size
            page_words = []
            for block in page.blocks:
                for line in block.lines:
                    for word in line.words:
                        (x_min_rel, y_min_rel), (x_max_rel, y_max_rel) = word.geometry
                        page_words.append({
                            "text": word.value,
                            "confidence": round(word.confidence, 4),
                            "bbox": [
                                int(x_min_rel * img_w),
                                int(y_min_rel * img_h),
                                int(x_max_rel * img_w),
                                int(y_max_rel * img_h),
                            ],
                        })
            all_pages.append({
                "image_width": img_w,
                "image_height": img_h,
                "words": page_words,
            })

    elapsed = time.perf_counter() - start
    return all_pages, elapsed


def run_doctr_multi_orientation(
    predictor,
    images: List[Image.Image],
    iou_threshold: float = 0.3,
    batch_size: int = 32,
) -> Tuple[list, float]:
    """
    Run DocTR at 0°, 90°, 180°, 270° and merge word detections via NMS.

    Processes pages in batches of `batch_size` to avoid GPU OOM.
    Words are unprojected back to original image space, then NMS selects the
    highest-confidence detection for each region.

    Returns same format as run_doctr(), but each word has an extra 'angle'
    field (0 / 90 / 180 / 270) indicating which rotation detected it.
    """
    angles = [0, 90, 180, 270]
    # Pre-allocate per-page word lists for each angle
    angle_results: Dict[int, list] = {a: [[] for _ in images] for a in angles}

    total_start = time.perf_counter()

    for angle in angles:
        for batch_start in range(0, len(images), batch_size):
            batch_imgs = images[batch_start:batch_start + batch_size]
            rotated_images = [_rotate_image(img, angle) for img in batch_imgs]
            np_images = [np.array(img.convert("RGB")) for img in rotated_images]

            result = predictor(np_images)

            for page_idx, page in enumerate(result.pages):
                global_idx = batch_start + page_idx
                orig_w, orig_h = images[global_idx].size
                rot_w, rot_h = rotated_images[page_idx].size
                for block in page.blocks:
                    for line in block.lines:
                        for word in line.words:
                            (x_min_rel, y_min_rel), (x_max_rel, y_max_rel) = word.geometry
                            rx1 = int(x_min_rel * rot_w)
                            ry1 = int(y_min_rel * rot_h)
                            rx2 = int(x_max_rel * rot_w)
                            ry2 = int(y_max_rel * rot_h)
                            orig_bbox = _unproject_bbox([rx1, ry1, rx2, ry2], angle, orig_w, orig_h)
                            orig_bbox = [
                                max(0, orig_bbox[0]),
                                max(0, orig_bbox[1]),
                                min(orig_w - 1, orig_bbox[2]),
                                min(orig_h - 1, orig_bbox[3]),
                            ]
                            angle_results[angle][global_idx].append({
                                "text": word.value,
                                "confidence": round(word.confidence, 4),
                                "bbox": orig_bbox,
                                "angle": angle,
                            })
            print(f"    angle={angle}° batch {batch_start//batch_size + 1}/{(len(images) + batch_size - 1)//batch_size} done ({len(batch_imgs)} pages)")

    elapsed = time.perf_counter() - total_start

    # Merge per page with NMS
    all_pages = []
    for page_idx, img in enumerate(images):
        orig_w, orig_h = img.size
        all_words = []
        for angle in angles:
            all_words.extend(angle_results[angle][page_idx])
        merged = _nms_words(all_words, iou_threshold=iou_threshold)
        all_pages.append({
            "image_width": orig_w,
            "image_height": orig_h,
            "words": merged,
        })

    return all_pages, elapsed
