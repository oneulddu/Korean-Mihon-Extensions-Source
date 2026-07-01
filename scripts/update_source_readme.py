#!/usr/bin/env python3
"""Regenerate the auto-managed extension table in the source repo README."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from list_extensions import (  # noqa: E402
    discover_extensions,
    is_buildable,
    load_config,
    parse_gradle_value,
)
from readme_utils import update_readme  # noqa: E402


def build_table(source_dir: Path) -> str:
    config = load_config(source_dir)
    rows = []
    for lang, module, build_file in discover_extensions(source_dir):
        text = build_file.read_text(encoding="utf-8")
        ext_name = parse_gradle_value(text, "extName") or module
        buildable, _ = is_buildable(source_dir, module, build_file, config, explicit=False)
        deploy = "✅" if buildable else "⏸️"
        rows.append((ext_name, lang, module, deploy))

    rows.sort(key=lambda item: item[0].lower())

    lines = [
        "| 확장 | 디렉터리 (`src/…`) | 배포 |",
        "| :--- | :--- | :--: |",
    ]
    for ext_name, lang, module, deploy in rows:
        lines.append(f"| {ext_name} | `{lang}/{module}` | {deploy} |")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, default=Path.cwd())
    parser.add_argument("--readme", type=Path, help="갱신할 README 경로 (기본: <source-dir>/README.md)")
    parser.add_argument("--check", action="store_true", help="변경 없이 갱신 필요 여부만 확인")
    args = parser.parse_args()

    source_dir = args.source_dir.resolve()
    readme_path = (args.readme or source_dir / "README.md").resolve()
    body = build_table(source_dir)

    if args.check:
        original = readme_path.read_text(encoding="utf-8") if readme_path.exists() else ""
        from readme_utils import replace_block

        if replace_block(original, body) != original:
            print(f"{readme_path} is out of date", file=sys.stderr)
            raise SystemExit(1)
        print(f"{readme_path} is up to date")
        return

    if update_readme(readme_path, body):
        print(f"Updated {readme_path}")
    else:
        print(f"No change for {readme_path}")


if __name__ == "__main__":
    main()
