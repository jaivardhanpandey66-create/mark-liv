import base64
import io
from pathlib import Path
from types import SimpleNamespace

from google import genai
from google.genai import types

from core import llm_client

FAST = "fast"
SMART = "smart"
SEARCH = "search"


class _Response:
    def __init__(self, text: str):
        self.text = text or ""
        part = SimpleNamespace(text=self.text)
        content = SimpleNamespace(parts=[part] if self.text else [])
        self.candidates = [SimpleNamespace(content=content)]


def _as_text(value) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    if isinstance(value, (list, tuple)):
        return "\n".join(_as_text(item) for item in value if _as_text(item))
    if hasattr(value, "parts"):
        return "\n".join(_as_text(item) for item in value.parts)
    text = getattr(value, "text", None)
    if text is not None:
        return str(text)
    return str(value)


def _system_instruction(config) -> str:
    if isinstance(config, dict):
        return _as_text(config.get("system_instruction") or config.get("systemInstruction"))
    return _as_text(getattr(config, "system_instruction", None))


def _image_data_url(mime_type: str, data: bytes) -> str:
    encoded = base64.b64encode(data).decode("ascii")
    return f"data:{mime_type or 'image/png'};base64,{encoded}"


def _item_to_part(item) -> dict | None:
    if isinstance(item, str):
        return {"type": "text", "text": item}
    if isinstance(item, Path):
        return {"type": "text", "text": str(item)}
    if isinstance(item, dict):
        inline = item.get("inline_data") or item.get("inlineData")
        if isinstance(inline, dict):
            data = inline.get("data", b"")
            mime = inline.get("mime_type") or inline.get("mimeType") or "image/png"
            if isinstance(data, str):
                data = data.encode("ascii")
            return {"type": "image_url", "image_url": {"url": _image_data_url(mime, data)}}
        if item.get("type") == "image_url":
            return item
        if item.get("text") is not None:
            return {"type": "text", "text": str(item["text"])}
        return None

    inline = getattr(item, "inline_data", None)
    if inline is not None:
        data = getattr(inline, "data", b"")
        mime = getattr(inline, "mime_type", None) or "image/png"
        if isinstance(data, str):
            data = data.encode("ascii")
        return {"type": "image_url", "image_url": {"url": _image_data_url(mime, data)}}

    if hasattr(item, "save") and hasattr(item, "size"):
        buffer = io.BytesIO()
        item.save(buffer, format="PNG")
        return {
            "type": "image_url",
            "image_url": {"url": _image_data_url("image/png", buffer.getvalue())},
        }

    text = getattr(item, "text", None)
    if text is not None:
        return {"type": "text", "text": str(text)}
    return None


def _to_openai_content(contents):
    if isinstance(contents, str):
        return contents
    if isinstance(contents, dict):
        return contents
    if hasattr(contents, "parts"):
        contents = contents.parts
    if isinstance(contents, (list, tuple)):
        parts = []
        for item in contents:
            converted = _item_to_part(item)
            if converted is not None:
                parts.append(converted)
        if parts and all(part.get("type") == "text" for part in parts):
            return "\n".join(part["text"] for part in parts)
        return parts or ""
    return _as_text(contents)


def _contains_image(contents) -> bool:
    if isinstance(contents, dict):
        if contents.get("type") == "image_url":
            return True
        return bool(contents.get("inline_data") or contents.get("inlineData"))
    if hasattr(contents, "parts"):
        return _contains_image(contents.parts)
    if isinstance(contents, (list, tuple)):
        return any(_contains_image(item) for item in contents)
    if hasattr(contents, "inline_data") and contents.inline_data is not None:
        return True
    return hasattr(contents, "save") and hasattr(contents, "size")


def _vision_model() -> str | None:
    cfg = llm_client._load_config()
    value = str(cfg.get("llm_vision_model", "")).strip()
    return value or None


def _tools_for_config(config) -> list[dict] | None:
    if llm_client.get_llm_provider() != "openrouter" or not isinstance(config, dict):
        return None
    converted = []
    for tool in config.get("tools") or []:
        if isinstance(tool, dict) and "google_search" in tool:
            converted.append({"type": "openrouter:web_search"})
        elif isinstance(tool, dict):
            converted.append(tool)
    return converted or None


def call(contents, tier: str = SMART, timeout_ms: int = 60_000, config=None) -> _Response:
    messages = []
    system = _system_instruction(config)
    if system:
        messages.append({"role": "system", "content": system})
    messages.append({"role": "user", "content": _to_openai_content(contents)})
    timeout = max(1, int(timeout_ms / 1000))
    model = _vision_model() if _contains_image(contents) else None
    tools = _tools_for_config(config)
    result = llm_client.call_llm(
        messages,
        tools=tools,
        timeout=timeout,
        model=model,
    )
    return _Response(str(result.get("content") or ""))


def text(
    prompt: str,
    tier: str = SMART,
    system: str | None = None,
    timeout_ms: int = 30_000,
) -> str:
    timeout = max(1, int(timeout_ms / 1000))
    return llm_client.call_llm_text(
        prompt,
        system=system,
        timeout=timeout,
    )


def build_client(timeout: float = 60.0):
    from memory.config_manager import get_gemini_key

    return genai.Client(
        api_key=get_gemini_key(),
        http_options=types.HttpOptions(timeout=timeout),
    )


def verify_key(key: str) -> bool:
    from memory.config_manager import normalize_gemini_key

    return bool(normalize_gemini_key(key))
