"""DocTR word-level bounding box detection."""

import time
import numpy as np
from typing import List, Dict, Tuple
from PIL import Image


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


def run_doctr(predictor, images: List[Image.Image]) -> Tuple[list, float]:
    """
    Run DocTR on a list of PIL images.

    Returns:
        (pages_words, elapsed_seconds)
        pages_words: list of dicts per page, each with:
            image_width, image_height, words: [{text, confidence, bbox: [x1,y1,x2,y2]}]
    """
    np_images = [np.array(img.convert("RGB")) for img in images]

    start = time.perf_counter()
    result = predictor(np_images)
    elapsed = time.perf_counter() - start

    all_pages = []
    for page_idx, page in enumerate(result.pages):
        img_w, img_h = images[page_idx].size
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

    return all_pages, elapsed
