"""
Multi-provider LLM client wrapper for vision/OCR tasks.

Provides an OpenAI-compatible interface regardless of the actual provider.
- vllm/fireworks/openai: Uses the OpenAI SDK directly
- claude: Uses httpx to call Anthropic Messages API, wraps response to match OpenAI format

Usage:
    client = create_llm_client(provider, api_base, api_key)
    # Then use client.chat.completions.create(...) as usual
"""

import os
import time
import random
import httpx
import base64
from dataclasses import dataclass
from typing import List, Dict, Optional, Any


# ─── Response wrappers (so Claude responses look like OpenAI responses) ──────

@dataclass
class _Message:
    content: str

@dataclass
class _Choice:
    message: _Message

@dataclass
class _ChatCompletion:
    choices: List[_Choice]


# ─── Claude adapter ─────────────────────────────────────────────────────────

class _ClaudeChatCompletions:
    """Mimics openai.chat.completions with Claude's Messages API."""

    def __init__(self, base_url: str, api_key: str):
        self.url = f"{base_url.rstrip('/')}/v1/messages"
        self.api_key = api_key
        self._client = httpx.Client(
            timeout=httpx.Timeout(120.0),
            headers={
                "Content-Type": "application/json",
                "x-api-key": api_key,
                "anthropic-version": "2023-06-01",
            },
        )

    def create(self, model: str, messages: List[Dict], max_tokens: int = 512,
               temperature: float = 0.0, **kwargs) -> _ChatCompletion:
        """Convert OpenAI-style messages to Claude format and call the API."""

        # Convert OpenAI vision messages to Claude format
        claude_content = []
        for msg in messages:
            if isinstance(msg.get("content"), list):
                # Multi-part message (text + images)
                for part in msg["content"]:
                    if part["type"] == "text":
                        claude_content.append({
                            "type": "text",
                            "text": part["text"],
                        })
                    elif part["type"] == "image_url":
                        # Extract base64 from data URI: "data:image/jpeg;base64,..."
                        url = part["image_url"]["url"]
                        if url.startswith("data:"):
                            # Parse: data:image/jpeg;base64,<data>
                            header, b64_data = url.split(",", 1)
                            media_type = header.split(":")[1].split(";")[0]
                            claude_content.append({
                                "type": "image",
                                "source": {
                                    "type": "base64",
                                    "media_type": media_type,
                                    "data": b64_data,
                                },
                            })
                        else:
                            # URL-based image (Claude supports this too)
                            claude_content.append({
                                "type": "image",
                                "source": {
                                    "type": "url",
                                    "url": url,
                                },
                            })
            elif isinstance(msg.get("content"), str):
                claude_content.append({
                    "type": "text",
                    "text": msg["content"],
                })

        payload = {
            "model": model,
            "max_tokens": max_tokens,
            "temperature": temperature,
            "messages": [
                {"role": "user", "content": claude_content},
            ],
        }

        # Retry with exponential backoff + jitter for rate limits (429) and overloaded (529)
        max_retries = 8
        for attempt in range(max_retries):
            response = self._client.post(self.url, json=payload)
            if response.status_code in (429, 529) and attempt < max_retries - 1:
                # Parse retry-after header if present, otherwise exponential backoff + jitter
                retry_after = response.headers.get("retry-after")
                if retry_after:
                    delay = float(retry_after) + random.uniform(0, 1)
                else:
                    delay = min(2 ** attempt, 60) + random.uniform(0, 2)
                print(f"      [Claude] Rate limited, retry {attempt+1}/{max_retries} in {delay:.1f}s...")
                time.sleep(delay)
                continue
            response.raise_for_status()
            break

        data = response.json()

        # Extract text from Claude response: {"content": [{"type": "text", "text": "..."}]}
        text_parts = [block["text"] for block in data.get("content", []) if block.get("type") == "text"]
        text = "".join(text_parts)

        return _ChatCompletion(choices=[_Choice(message=_Message(content=text))])


class _ClaudeChat:
    def __init__(self, base_url: str, api_key: str):
        self.completions = _ClaudeChatCompletions(base_url, api_key)


class ClaudeClient:
    """Drop-in replacement for OpenAI() that uses Claude's Messages API."""

    def __init__(self, base_url: str, api_key: str):
        self.chat = _ClaudeChat(base_url, api_key)


# ─── Factory ─────────────────────────────────────────────────────────────────

def create_llm_client(provider: str, api_base: str, api_key: str):
    """
    Create an LLM client based on provider.

    Returns an object with .chat.completions.create() interface
    (OpenAI SDK for vllm/fireworks/openai, ClaudeClient for claude).
    """
    provider = provider.lower().strip()

    if provider == "claude":
        print(f"[LLM] Using Claude client → {api_base}")
        return ClaudeClient(base_url=api_base, api_key=api_key)
    else:
        from openai import OpenAI
        print(f"[LLM] Using OpenAI-compatible client ({provider}) → {api_base}")
        return OpenAI(base_url=api_base, api_key=api_key)
