#!/usr/bin/env python3
"""Update the Korean Mihon extension deployment repository files."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Any

META = {
    "repo_name": "Korean Mihon Extensions",
    "badge_label": "KOR",
    "website": "https://github.com/oneulddu/Korean-Mihon-Extensions",
    "source": "https://github.com/oneulddu/Korean-Mihon-Extensions-Source",
    "deploy_owner_repo": "oneulddu/Korean-Mihon-Extensions",
    "deploy_branch": "repo",
    "signing_key": "b25af02d178fad20ebe739e59336f2ae5e307dcd1375418278e752dba03497cb",
    "extension_lib": "1.4",
}

APK_RE = re.compile(r"^tachiyomi-(?P<lang>[a-z-]+)\.(?P<module>[a-z0-9_.-]+)-v(?P<version>\d+\.\d+\.(?P<code>\d+))-release\.apk$")


@dataclass(frozen=True)
class ExtensionInfo:
    lang: str
    module: str
    name: str
    legacy_name: str
    package_name: str
    version_name: str
    version_code: int
    nsfw: int
    sources: list[dict[str, str]]
    apk_path: Path
    icon_path: Path


def read_json(path: Path, default: Any) -> Any:
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path: Path, data: object, *, minify: bool = False) -> None:
    if minify:
        text = json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n"
    else:
        text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    path.write_text(text, encoding="utf-8")


def parse_requested(value: str, known: set[str] | None = None) -> set[str] | None:
    normalized = value.strip()
    if not normalized or normalized.lower() == "all":
        return None
    requested = {item for item in re.split(r"[\s,]+", normalized) if item}
    if known is not None:
        unknown = requested - known
        if unknown:
            raise SystemExit(f"Unknown extension module(s): {', '.join(sorted(unknown))}")
    return requested


def parse_gradle_value(text: str, key: str) -> str | None:
    match = re.search(rf"\b{re.escape(key)}\s*=\s*['\"]([^'\"]+)['\"]", text)
    return match.group(1) if match else None


def parse_gradle_bool(text: str, key: str, default: bool = False) -> bool:
    match = re.search(rf"\b{re.escape(key)}\s*=\s*(true|false)", text)
    return (match.group(1) == "true") if match else default


def parse_gradle_int(text: str, key: str) -> int | None:
    match = re.search(rf"\b{re.escape(key)}\s*=\s*(\d+)", text)
    return int(match.group(1)) if match else None


def generate_source_id(name: str, lang: str, version_id: int = 1) -> str:
    digest = hashlib.md5(f"{name.lower()}/{lang}/{version_id}".encode()).digest()[:8]
    return str(int.from_bytes(digest, "big") & 0x7FFFFFFFFFFFFFFF)


def discover_modules(source_dir: Path) -> dict[str, tuple[str, Path, str]]:
    modules: dict[str, tuple[str, Path, str]] = {}
    for build_file in sorted((source_dir / "src").glob("*/*/build.gradle")):
        lang = build_file.parent.parent.name
        module = build_file.parent.name
        modules[module] = (lang, build_file.parent, build_file.read_text(encoding="utf-8"))
    return modules


def find_icon(module_dir: Path) -> Path:
    preferred = [
        module_dir / "res/mipmap-xxxhdpi/ic_launcher.png",
        module_dir / "res/mipmap-xxhdpi/ic_launcher.png",
        module_dir / "res/mipmap-xhdpi/ic_launcher.png",
        module_dir / "res/mipmap-hdpi/ic_launcher.png",
        module_dir / "res/mipmap-mdpi/ic_launcher.png",
    ]
    for path in preferred:
        if path.exists():
            return path
    icons = sorted((module_dir / "res").glob("**/ic_launcher.png"))
    if icons:
        return icons[-1]
    raise SystemExit(f"Icon not found for {module_dir}")


def kotlin_text(module_dir: Path) -> str:
    parts = []
    for path in sorted((module_dir / "src").glob("**/*.kt")):
        parts.append(path.read_text(encoding="utf-8"))
    return "\n".join(parts)


def infer_sources(module_dir: Path, ext_name: str, ext_lang: str) -> list[dict[str, str]]:
    text = kotlin_text(module_dir)
    name_match = re.search(r"override\s+val\s+name(?:\s*:\s*String)?\s*=\s*['\"]([^'\"]+)['\"]", text)
    lang_match = re.search(r"override\s+val\s+lang(?:\s*:\s*String)?\s*=\s*['\"]([^'\"]+)['\"]", text)
    base_match = re.search(r"override\s+val\s+baseUrl(?:\s*:\s*String)?\s*=\s*['\"]([^'\"]+)['\"]", text)
    default_base_match = re.search(r"defaultBaseUrl\s*=\s*['\"]([^'\"]+)['\"]", text)
    madara_match = re.search(r"Madara\(\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]\s*,\s*['\"]([^'\"]+)['\"]", text, re.S)
    version_match = re.search(r"override\s+val\s+versionId\s*=\s*(\d+)", text)

    if madara_match:
        name, base_url, lang = madara_match.groups()
    else:
        name = name_match.group(1) if name_match else ext_name
        lang = lang_match.group(1) if lang_match else ext_lang
        base_url = (base_match or default_base_match)
        if not base_url:
            raise SystemExit(f"Cannot infer baseUrl for {module_dir.name}; add it to scripts/extensions.json")
        base_url = base_url.group(1)

    version_id = int(version_match.group(1)) if version_match else 1
    return [{"name": name, "lang": lang, "baseUrl": base_url, "id": generate_source_id(name, lang, version_id)}]


def normalize_sources(raw_sources: list[dict[str, Any]], default_lang: str) -> list[dict[str, str]]:
    sources = []
    for source in raw_sources:
        name = str(source["name"])
        lang = str(source.get("lang") or default_lang)
        version_id = int(source.get("versionId", 1))
        source_id = str(source.get("id") or generate_source_id(name, lang, version_id))
        sources.append({
            "name": name,
            "lang": lang,
            "id": source_id,
            "baseUrl": str(source["baseUrl"]),
        })
    return sources


def find_release_apks(source_dir: Path, requested: set[str] | None) -> list[Path]:
    apks = []
    for apk in sorted(source_dir.glob("src/*/*/build/outputs/apk/release/tachiyomi-*-v*-release.apk")):
        match = APK_RE.match(apk.name)
        if not match:
            continue
        if requested is not None and match.group("module") not in requested:
            continue
        apks.append(apk)
    return apks


def build_extension_info(source_dir: Path, apk_path: Path, modules: dict[str, tuple[str, Path, str]], config: dict[str, Any]) -> ExtensionInfo:
    match = APK_RE.match(apk_path.name)
    if not match:
        raise SystemExit(f"Unexpected APK filename: {apk_path.name}")

    apk_lang = match.group("lang")
    module = match.group("module")
    version_name = match.group("version")
    version_code = int(match.group("code"))

    if module not in modules:
        raise SystemExit(f"Cannot find source module for APK: {apk_path}")

    module_lang, module_dir, gradle_text = modules[module]
    lang = module_lang or apk_lang
    ext_name = parse_gradle_value(gradle_text, "extName") or module
    nsfw = 1 if parse_gradle_bool(gradle_text, "isNsfw") else 0
    package_name = f"eu.kanade.tachiyomi.extension.{lang}.{module}"

    ext_config = config.get("extensions", {}).get(module, {})
    raw_sources = ext_config.get("sources")
    sources = normalize_sources(raw_sources, lang) if raw_sources else infer_sources(module_dir, ext_name, lang)

    return ExtensionInfo(
        lang=lang,
        module=module,
        name=str(ext_config.get("name") or ext_name),
        legacy_name=f"Tachiyomi: {ext_config.get('name') or ext_name}",
        package_name=str(ext_config.get("packageName") or package_name),
        version_name=version_name,
        version_code=version_code,
        nsfw=nsfw,
        sources=sources,
        apk_path=apk_path,
        icon_path=find_icon(module_dir),
    )


def legacy_entry(info: ExtensionInfo, apk_name: str) -> dict[str, Any]:
    return {
        "name": info.legacy_name,
        "pkg": info.package_name,
        "apk": apk_name,
        "lang": info.lang,
        "code": info.version_code,
        "version": info.version_name,
        "nsfw": info.nsfw,
        "sources": info.sources,
    }


def modern_entry(info: ExtensionInfo, apk_name: str, icon_name: str) -> dict[str, Any]:
    rating = "CONTENT_RATING_PORNOGRAPHIC" if info.nsfw else "CONTENT_RATING_SAFE"
    return {
        "name": info.name,
        "packageName": info.package_name,
        "resources": {
            "apkUrl": f"https://raw.githubusercontent.com/{META['deploy_owner_repo']}/refs/heads/{META['deploy_branch']}/apk/{apk_name}",
            "iconUrl": f"https://raw.githubusercontent.com/{META['deploy_owner_repo']}/refs/heads/{META['deploy_branch']}/icon/{icon_name}",
        },
        "extensionLib": META["extension_lib"],
        "versionCode": info.version_code,
        "versionName": info.version_name,
        "sources": [
            {
                "id": source["id"],
                "name": source["name"],
                "language": source["lang"],
                "homeUrl": source["baseUrl"],
                "contentRating": rating,
            }
            for source in info.sources
        ],
    }


def read_existing_legacy(deploy_dir: Path) -> dict[str, dict[str, Any]]:
    data = read_json(deploy_dir / "index.min.json", [])
    return {entry["pkg"]: entry for entry in data if isinstance(entry, dict) and "pkg" in entry}


def read_existing_modern(deploy_dir: Path) -> dict[str, dict[str, Any]]:
    data = read_json(deploy_dir / "index.json", {})
    entries = data.get("extensions", []) if isinstance(data, dict) else []
    return {entry["packageName"]: entry for entry in entries if isinstance(entry, dict) and "packageName" in entry}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, default=Path.cwd())
    parser.add_argument("--deploy-dir", type=Path, required=True)
    parser.add_argument("--extensions", default="all", help="all 또는 공백/쉼표로 구분한 모듈명")
    parser.add_argument("--replace", action="store_true", help="기존 인덱스를 버리고 이번에 찾은 APK만 반영")
    args = parser.parse_args()

    source_dir = args.source_dir.resolve()
    deploy_dir = args.deploy_dir.resolve()
    config = read_json(source_dir / "scripts/extensions.json", {"extensions": {}})
    modules = discover_modules(source_dir)
    requested = parse_requested(args.extensions, set(modules))
    apks = find_release_apks(source_dir, requested)
    if not apks:
        raise SystemExit("No release APKs found. Build extensions before updating the deployment repository.")

    infos = [build_extension_info(source_dir, apk, modules, config) for apk in apks]
    infos.sort(key=lambda item: (item.lang, item.name.lower(), item.module))

    (deploy_dir / "apk").mkdir(parents=True, exist_ok=True)
    (deploy_dir / "icon").mkdir(parents=True, exist_ok=True)

    legacy_entries = {} if args.replace else read_existing_legacy(deploy_dir)
    modern_entries = {} if args.replace else read_existing_modern(deploy_dir)

    for info in infos:
        apk_name = info.apk_path.name
        icon_name = f"{info.package_name}.png"
        apk_prefix = f"tachiyomi-{info.lang}.{info.module}-v"
        for old_apk in (deploy_dir / "apk").glob(f"{apk_prefix}*.apk"):
            if old_apk.name != apk_name:
                old_apk.unlink()
        shutil.copy2(info.apk_path, deploy_dir / "apk" / apk_name)
        shutil.copy2(info.icon_path, deploy_dir / "icon" / icon_name)
        legacy_entries[info.package_name] = legacy_entry(info, apk_name)
        modern_entries[info.package_name] = modern_entry(info, apk_name, icon_name)

    sorted_legacy = [legacy_entries[key] for key in sorted(legacy_entries, key=lambda key: legacy_entries[key]["name"].lower())]
    sorted_modern = [modern_entries[key] for key in sorted(modern_entries, key=lambda key: modern_entries[key]["name"].lower())]

    modern_index = {
        "name": META["repo_name"],
        "badgeLabel": META["badge_label"],
        "signingKey": META["signing_key"],
        "contact": {
            "website": META["website"],
            "source": META["source"],
        },
        "extensions": sorted_modern,
    }

    repo_json = {
        "meta": {
            "name": META["repo_name"],
            "website": META["website"],
            "signingKeyFingerprint": META["signing_key"],
        },
    }

    write_json(deploy_dir / "index.min.json", sorted_legacy, minify=True)
    write_json(deploy_dir / "index.json", modern_index)
    write_json(deploy_dir / "repo.json", repo_json)


if __name__ == "__main__":
    main()
