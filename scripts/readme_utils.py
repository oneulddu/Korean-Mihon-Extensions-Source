#!/usr/bin/env python3
"""Shared helpers for rendering the auto-managed extension tables in README files.

두 저장소의 README에는 확장 목록 표가 있고, 그 표만 아래 마커 사이에서
스크립트가 자동으로 다시 그린다. 마커 밖의 꾸밈/설명은 그대로 보존된다.
"""

from __future__ import annotations

import re
from pathlib import Path
from urllib.parse import urlsplit

MARKER_START = "<!-- extensions:start -->"
MARKER_END = "<!-- extensions:end -->"

_BLOCK_RE = re.compile(
    re.escape(MARKER_START) + r".*?" + re.escape(MARKER_END),
    re.DOTALL,
)


def wrap_block(body: str) -> str:
    """Wrap a rendered table body with the managed markers."""

    return f"{MARKER_START}\n{body.strip()}\n{MARKER_END}"


def replace_block(text: str, body: str) -> str:
    """Replace the managed block in *text* with *body*.

    마커가 없으면 원본을 그대로 돌려준다(호출부에서 처리).
    """

    block = wrap_block(body)
    if _BLOCK_RE.search(text):
        return _BLOCK_RE.sub(lambda _: block, text, count=1)
    return text


def has_block(text: str) -> bool:
    return bool(_BLOCK_RE.search(text))


def update_readme(path: Path, body: str) -> bool:
    """Rewrite the managed block in *path*.

    Returns True when the file content changed.
    """

    if not path.exists():
        return False
    original = path.read_text(encoding="utf-8")
    if not has_block(original):
        return False
    updated = replace_block(original, body)
    if updated == original:
        return False
    path.write_text(updated, encoding="utf-8")
    return True


def pretty_host(url: str) -> str:
    """Return a compact host+path label for a source URL."""

    parts = urlsplit(url)
    host = parts.netloc or parts.path
    label = host + (parts.path if parts.netloc else "")
    return label.rstrip("/") or url


def short_package(package_name: str) -> str:
    """Shorten a package name to its last two dotted segments for tables."""

    segments = package_name.split(".")
    if len(segments) <= 2:
        return package_name
    return "…" + ".".join(segments[-2:])
