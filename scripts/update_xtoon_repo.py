#!/usr/bin/env python3
"""Update the Korean Mihon extension deployment repository files."""

from __future__ import annotations

import argparse
import json
import re
import shutil
from pathlib import Path

META = {
    "repo_name": "Korean Mihon Extensions",
    "badge_label": "KOR",
    "website": "https://github.com/oneulddu/Korean-Mihon-Extensions",
    "source": "https://github.com/oneulddu/Korean-Mihon-Extensions-Source",
    "deploy_owner_repo": "oneulddu/Korean-Mihon-Extensions",
    "deploy_branch": "repo",
    "signing_key": "62aaff9a192e8d3e462b352a4b435bcacc38c2390aa9c0dbf9c863942401adf0",
    "name": "Xtoon",
    "legacy_name": "Tachiyomi: Xtoon",
    "pkg": "eu.kanade.tachiyomi.extension.ko.xtoon",
    "apk_prefix": "tachiyomi-ko.xtoon-v",
    "lang": "ko",
    "source_id": "1732858837197401514",
    "base_url": "https://t3.xtoon365.com",
    "nsfw": 1,
    "content_rating": "CONTENT_RATING_PORNOGRAPHIC",
}


def parse_version_code(source_dir: Path) -> int:
    gradle_file = source_dir / "src/ko/xtoon/build.gradle"
    text = gradle_file.read_text(encoding="utf-8")
    match = re.search(r"extVersionCode\s*=\s*(\d+)", text)
    if not match:
        raise SystemExit(f"extVersionCode not found in {gradle_file}")
    return int(match.group(1))


def write_json(path: Path, data: object, *, minify: bool = False) -> None:
    if minify:
        text = json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n"
    else:
        text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    path.write_text(text, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", type=Path, default=Path.cwd())
    parser.add_argument("--deploy-dir", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--icon", type=Path, default=None)
    args = parser.parse_args()

    source_dir = args.source_dir.resolve()
    deploy_dir = args.deploy_dir.resolve()
    apk_path = args.apk.resolve()
    icon_path = (args.icon or source_dir / "src/ko/xtoon/res/mipmap-xxxhdpi/ic_launcher.png").resolve()

    if not apk_path.exists():
        raise SystemExit(f"APK not found: {apk_path}")
    if not icon_path.exists():
        raise SystemExit(f"Icon not found: {icon_path}")

    version_code = parse_version_code(source_dir)
    version_name = f"1.4.{version_code}"
    apk_name = f"tachiyomi-ko.xtoon-v{version_name}-release.apk"
    icon_name = f"{META['pkg']}.png"

    (deploy_dir / "apk").mkdir(parents=True, exist_ok=True)
    (deploy_dir / "icon").mkdir(parents=True, exist_ok=True)

    for old_apk in (deploy_dir / "apk").glob(f"{META['apk_prefix']}*.apk"):
        old_apk.unlink()

    shutil.copy2(apk_path, deploy_dir / "apk" / apk_name)
    shutil.copy2(icon_path, deploy_dir / "icon" / icon_name)

    legacy_entry = {
        "name": META["legacy_name"],
        "pkg": META["pkg"],
        "apk": apk_name,
        "lang": META["lang"],
        "code": version_code,
        "version": version_name,
        "nsfw": META["nsfw"],
        "sources": [
            {
                "name": META["name"],
                "lang": META["lang"],
                "id": META["source_id"],
                "baseUrl": META["base_url"],
            },
        ],
    }

    modern_index = {
        "name": META["repo_name"],
        "badgeLabel": META["badge_label"],
        "signingKey": META["signing_key"],
        "contact": {
            "website": META["website"],
            "source": META["source"],
        },
        "extensions": [
            {
                "name": META["name"],
                "packageName": META["pkg"],
                "resources": {
                    "apkUrl": f"https://raw.githubusercontent.com/{META['deploy_owner_repo']}/refs/heads/{META['deploy_branch']}/apk/{apk_name}",
                    "iconUrl": f"https://raw.githubusercontent.com/{META['deploy_owner_repo']}/refs/heads/{META['deploy_branch']}/icon/{icon_name}",
                },
                "extensionLib": "1.4",
                "versionCode": version_code,
                "versionName": version_name,
                "sources": [
                    {
                        "id": META["source_id"],
                        "name": META["name"],
                        "language": META["lang"],
                        "homeUrl": META["base_url"],
                        "contentRating": META["content_rating"],
                    },
                ],
            },
        ],
    }

    repo_json = {
        "meta": {
            "name": META["repo_name"],
            "website": META["website"],
            "signingKeyFingerprint": META["signing_key"],
        },
    }

    write_json(deploy_dir / "index.min.json", [legacy_entry], minify=True)
    write_json(deploy_dir / "index.json", modern_index)
    write_json(deploy_dir / "repo.json", repo_json)

    readme = f"""# Korean Mihon Extensions Repo

한국어 Mihon/Tachiyomi 확장 배포 전용 저장소입니다.

## Mihon 저장소 추가 URL

Keiyoushi 배포 레포처럼 `{META['deploy_branch']}` 브랜치를 배포 브랜치로 사용합니다.

```text
https://raw.githubusercontent.com/{META['deploy_owner_repo']}/{META['deploy_branch']}/index.min.json
```

## 배포 파일

- APK: `apk/{apk_name}`
- 아이콘: `icon/{icon_name}`
- 구형 저장소 목록: `index.min.json`
- 신형 저장소 목록: `index.json`
- 저장소 정보: `repo.json`

## 소스 코드

확장 소스와 Gradle 빌드 환경은 별도 레포로 분리했습니다.

```text
{META['source']}
```

## 현재 배포 버전

```text
version: {version_name}
versionCode: {version_code}
baseUrl: {META['base_url']}
```

## 서명 정보

현재 APK는 release 키로 서명되어 있습니다.

```text
SHA-256: {META['signing_key']}
```

`signingkey.jks`와 `signing.env`는 Git에 올리지 않고 별도로 보관합니다.

## GitHub Actions

자동 빌드/자동 인덱스 갱신은 소스 레포에서 관리합니다.

```text
{META['source']}/actions/workflows/build_xtoon_release.yml
```

해당 workflow가 Xtoon APK를 빌드하고 이 레포의 `{META['deploy_branch']}` 브랜치에 APK, 아이콘, `index.json`, `index.min.json`, `repo.json`을 갱신합니다.

## 주의

로그인, 결제, 성인 인증, 캡차, 차단 우회, DRM 우회 목적의 구현은 포함하지 않습니다.
"""
    (deploy_dir / "README.md").write_text(readme, encoding="utf-8")

    index_html = f"""<!doctype html>
<html lang=\"ko\">
<head>
  <meta charset=\"utf-8\">
  <meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">
  <title>Korean Mihon Extensions Repo</title>
  <style>
    body {{ font-family: system-ui, sans-serif; max-width: 760px; margin: 48px auto; padding: 0 20px; line-height: 1.6; }}
    code {{ background: #f3f3f3; padding: 2px 6px; border-radius: 4px; }}
  </style>
</head>
<body>
  <h1>Korean Mihon Extensions Repo</h1>
  <p>Mihon 저장소 URL:</p>
  <p><code>https://raw.githubusercontent.com/{META['deploy_owner_repo']}/{META['deploy_branch']}/index.min.json</code></p>
  <p>Source: <a href=\"{META['source']}\">Korean-Mihon-Extensions-Source</a></p>
</body>
</html>
"""
    (deploy_dir / "index.html").write_text(index_html, encoding="utf-8")


if __name__ == "__main__":
    main()
