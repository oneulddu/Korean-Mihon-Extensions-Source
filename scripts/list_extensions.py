#!/usr/bin/env python3
"""List buildable extension modules for this source repository."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path


def load_config(source_dir: Path) -> dict:
    path = source_dir / "scripts/extensions.json"
    if not path.exists():
        return {"extensions": {}}
    return json.loads(path.read_text(encoding="utf-8"))


def parse_gradle_value(text: str, key: str) -> str | None:
    match = re.search(rf"\b{re.escape(key)}\s*=\s*['\"]([^'\"]+)['\"]", text)
    return match.group(1) if match else None


def discover_extensions(source_dir: Path) -> list[tuple[str, str, Path]]:
    src_dir = source_dir / "src"
    found: list[tuple[str, str, Path]] = []
    for build_file in sorted(src_dir.glob("*/*/build.gradle")):
        lang = build_file.parent.parent.name
        module = build_file.parent.name
        found.append((lang, module, build_file))
    return found


def parse_requested(value: str, known: set[str]) -> set[str]:
    normalized = value.strip()
    if not normalized or normalized.lower() == "all":
        return set(known)
    return {item for item in re.split(r"[\s,]+", normalized) if item}


def is_buildable(source_dir: Path, module: str, build_file: Path, config: dict, explicit: bool) -> tuple[bool, str | None]:
    ext_config = config.get("extensions", {}).get(module, {})
    if ext_config.get("build") is False and not explicit:
        return False, ext_config.get("buildReason") or "disabled in scripts/extensions.json"

    text = build_file.read_text(encoding="utf-8")
    theme_pkg = parse_gradle_value(text, "themePkg")
    if theme_pkg and not (source_dir / "lib-multisrc" / theme_pkg).exists() and not explicit:
        return False, f"missing lib-multisrc/{theme_pkg}"

    return True, None


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, default=Path.cwd())
    parser.add_argument("--extensions", default="all", help="all 또는 공백/쉼표로 구분한 모듈명")
    parser.add_argument("--gradle-tasks", action="store_true")
    parser.add_argument("--names", action="store_true")
    args = parser.parse_args()

    source_dir = args.source_dir.resolve()
    config = load_config(source_dir)
    discovered = discover_extensions(source_dir)
    known = {module for _, module, _ in discovered}
    requested = parse_requested(args.extensions, known)
    unknown = requested - known
    if unknown:
        raise SystemExit(f"Unknown extension module(s): {', '.join(sorted(unknown))}")

    explicit = args.extensions.strip().lower() != "all"
    selected: list[tuple[str, str]] = []
    skipped: list[str] = []
    for lang, module, build_file in discovered:
        if module not in requested:
            continue
        buildable, reason = is_buildable(source_dir, module, build_file, config, explicit)
        if not buildable:
            skipped.append(f"{module}: {reason}")
            continue
        selected.append((lang, module))

    for message in skipped:
        print(f"skip {message}", file=sys.stderr)

    if args.gradle_tasks:
        print(" ".join(f":src:{lang}:{module}:assembleRelease" for lang, module in selected))
    else:
        print(" ".join(module for _, module in selected))


if __name__ == "__main__":
    main()
