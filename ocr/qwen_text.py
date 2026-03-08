"""Qwen VLM line-crop text recognition via OpenAI-compatible API."""

import io
import base64
import time
from difflib import SequenceMatcher
from typing import List, Dict, Tuple
from concurrent.futures import ThreadPoolExecutor, as_completed

from PIL import Image
from openai import OpenAI

from .line_grouping import group_words_into_lines, crop_line_region


def _pil_to_base64(image: Image.Image) -> str:
    buffer = io.BytesIO()
    image.save(buffer, format="JPEG", quality=95)
    buffer.seek(0)
    return base64.b64encode(buffer.read()).decode("utf-8")


def _qwen_read_line(client: OpenAI, b64_image: str, model_name: str, max_tokens: int) -> str:
    """Send a line crop to Qwen, return recognized text."""
    response = client.chat.completions.create(
        model=model_name,
        messages=[{
            "role": "user",
            "content": [
                {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{b64_image}"}},
                {"type": "text", "text": "Read this text exactly as written. Return ONLY the text, nothing else. No quotes, no explanation."},
            ],
        }],
        max_tokens=max_tokens,
        temperature=0.0,
    )
    return response.choices[0].message.content.strip()


def _fuzzy_sim(a: str, b: str) -> float:
    """Case-insensitive fuzzy similarity between two strings."""
    return SequenceMatcher(None, a.lower(), b.lower()).ratio()


def _map_text_to_words(qwen_text: str, line_words: List[Dict]) -> List[Dict]:
    """Map Qwen text tokens back to DocTR word boxes using sequential fuzzy alignment.

    For each DocTR box (left-to-right), tries consuming 1, 2, or 3 Qwen tokens
    and picks the span with the highest fuzzy similarity to DocTR's original text.
    This handles cases where DocTR merges words (e.g. "s*Brown,") that Qwen splits
    into separate tokens ("S.", "Brown,").
    """
    tokens = qwen_text.split()
    n_boxes = len(line_words)
    n_tokens = len(tokens)

    if not tokens or not line_words:
        return [dict(w) for w in line_words]

    # Simple case: same count → 1:1 mapping
    if n_tokens == n_boxes:
        updated = []
        for i, word in enumerate(line_words):
            w = dict(word)
            w["text"] = tokens[i]
            updated.append(w)
        return updated

    # Single box → assign all text
    if n_boxes == 1:
        w = dict(line_words[0])
        w["text"] = qwen_text
        return [w]

    # Sequential fuzzy alignment
    updated = []
    tok_idx = 0

    for box_idx, word in enumerate(line_words):
        boxes_remaining = n_boxes - box_idx
        tokens_remaining = n_tokens - tok_idx

        # No tokens left → keep DocTR text
        if tokens_remaining <= 0:
            updated.append(dict(word))
            continue

        # If remaining tokens == remaining boxes, force 1:1 to avoid starvation
        if tokens_remaining <= boxes_remaining:
            w = dict(word)
            if tok_idx < n_tokens:
                w["text"] = tokens[tok_idx]
                tok_idx += 1
            updated.append(w)
            continue

        # Try consuming 1, 2, or 3 tokens — pick best fuzzy match to DocTR text
        doctr_text = word["text"]
        max_span = min(3, tokens_remaining - (boxes_remaining - 1))  # leave at least 1 per remaining box

        best_span = 1
        best_score = -1.0

        for span in range(1, max_span + 1):
            candidate = " ".join(tokens[tok_idx:tok_idx + span])
            score = _fuzzy_sim(doctr_text, candidate)
            if score > best_score:
                best_score = score
                best_span = span

        w = dict(word)
        w["text"] = " ".join(tokens[tok_idx:tok_idx + best_span])
        tok_idx += best_span
        updated.append(w)

    # Any leftover tokens → append to last box
    if tok_idx < n_tokens and updated:
        extra = " ".join(tokens[tok_idx:])
        updated[-1]["text"] = updated[-1]["text"] + " " + extra

    return updated


def enhance_with_qwen(
    image: Image.Image,
    words: List[Dict],
    client: OpenAI,
    model_name: str,
    max_tokens: int = 512,
    max_workers: int = 4,
    y_tolerance: int = 15,
    line_padding: int = 5,
) -> Tuple[List[Dict], int, int]:
    """
    Enhance DocTR words with Qwen text recognition via line crops.

    Returns:
        (updated_words, success_count, fail_count)
    """
    lines = group_words_into_lines(words, y_tolerance=y_tolerance)
    successes = 0
    failures = 0

    def process_line(line_idx, line_words):
        crop, _ = crop_line_region(image, line_words, padding=line_padding)
        b64 = _pil_to_base64(crop)
        try:
            text = _qwen_read_line(client, b64, model_name, max_tokens)
            return _map_text_to_words(text, line_words), True
        except Exception as e:
            print(f"      Line {line_idx}: Qwen failed ({e})")
            return line_words, False  # Keep DocTR text

    results = [None] * len(lines)
    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        futures = {}
        for idx, line in enumerate(lines):
            future = pool.submit(process_line, idx, line)
            futures[future] = idx

        for future in as_completed(futures):
            idx = futures[future]
            updated, ok = future.result()
            results[idx] = updated
            if ok:
                successes += 1
            else:
                failures += 1

    all_words = []
    for line_result in results:
        if line_result:
            all_words.extend(line_result)

    return all_words, successes, failures
