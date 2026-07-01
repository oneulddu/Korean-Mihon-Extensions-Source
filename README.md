<div align="center">

# 🛠️ Korean Mihon Extensions — Source

**한국어 만화·웹툰 [Mihon](https://mihon.app) / Tachiyomi 확장의 소스 코드 & 빌드 환경**

[![Distribution](https://img.shields.io/badge/배포_레포-Korean--Mihon--Extensions-2979FF?style=for-the-badge&logo=github&logoColor=white)](https://github.com/oneulddu/Korean-Mihon-Extensions)
[![Build](https://img.shields.io/badge/CI-GitHub_Actions-2088FF?style=for-the-badge&logo=githubactions&logoColor=white)](https://github.com/oneulddu/Korean-Mihon-Extensions-Source/actions/workflows/build_extensions_release.yml)
![Language](https://img.shields.io/badge/Language-한국어-success?style=for-the-badge)

</div>

---

## ✨ 소개

이 저장소는 한국어 만화·웹툰 사이트용 Mihon / Tachiyomi 확장의 **소스 코드와 Gradle 빌드 환경**을 관리합니다.
빌드된 APK·인덱스는 별도의 **배포 전용 저장소**로 자동 배포됩니다.

```text
배포 레포 → https://github.com/oneulddu/Korean-Mihon-Extensions
Mihon URL → https://raw.githubusercontent.com/oneulddu/Korean-Mihon-Extensions/repo/index.min.json
```

---

## 📦 포함 확장

Keiyoushi `extensions-source` PR #15649 기준 한국어 확장들을 함께 가져왔습니다.
소스의 9개 확장 모두 배포 레포(`repo` 브랜치)로 배포됩니다.

<!-- 아래 표는 scripts/update_source_readme.py가 자동으로 갱신합니다. 직접 수정하지 마세요. -->
<!-- extensions:start -->
| 확장 | 디렉터리 (`src/…`) | 배포 |
| :--- | :--- | :--: |
| 11toon | `ko/toon11` | ✅ |
| BlackToon | `ko/blacktoon` | ✅ |
| Jjaptoon | `ko/jjaptoon` | ✅ |
| Manatoki | `ko/manatoki` | ✅ |
| Naver Comic | `ko/navercomic` | ✅ |
| NTK | `ko/ntk` | ✅ |
| RawDEX | `ko/rawdex` | ✅ |
| Toonkor | `ko/toonkor` | ✅ |
| Wolf.com | `ko/wolfdotcom` | ✅ |
| Xtoon | `ko/xtoon` | ✅ |
<!-- extensions:end -->

> `newtoki` 이름으로 남아 있는 최종 디렉터리는 없고, 해당 PR 최종 상태에서는 `ntk` 패키지로 정리되어 있습니다.

---

## 🏗️ 빌드

Java(JDK 21)와 Android SDK가 필요합니다.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

# 특정 확장만 빌드
./gradlew $(python3 scripts/list_extensions.py --gradle-tasks --extensions "xtoon ntk")
```

---

## 🤖 자동 빌드 & 배포

[`.github/workflows/build_extensions_release.yml`](.github/workflows/build_extensions_release.yml)가
한국어 확장을 빌드한 뒤, [`oneulddu/Korean-Mihon-Extensions`](https://github.com/oneulddu/Korean-Mihon-Extensions)의
`repo` 브랜치에 APK·아이콘·인덱스와 배포 레포 README 표를 자동 갱신하고,
이 레포의 확장 표(`README.md`)도 함께 최신 상태로 맞춥니다.

**필요한 GitHub Secrets**

| Secret | 설명 |
| :--- | :--- |
| `SIGNING_KEYSTORE_BASE64` | `signingkey.jks`를 base64 인코딩한 값 |
| `KEY_STORE_PASSWORD` | 키스토어 비밀번호 |
| `ALIAS` | 키 별칭 |
| `KEY_PASSWORD` | 키 비밀번호 |
| `DEPLOY_TOKEN` | 배포 레포 contents write 권한 토큰 |

> 서명키와 토큰은 민감 정보라 레포에는 포함하지 않습니다.

---

## 🧩 배포 스크립트 구조

| 스크립트 | 역할 |
| :--- | :--- |
| `scripts/list_extensions.py` | 빌드할 확장 모듈을 찾아 Gradle task 목록 생성 |
| `scripts/update_repo.py` | 빌드된 APK를 `index.json`·`index.min.json`·`repo.json`에 반영하고 배포 레포 `README.md` 확장 표까지 자동 갱신 |
| `scripts/update_source_readme.py` | 이 레포 `README.md`의 확장 표를 소스 기준으로 자동 갱신 |
| `scripts/extensions.json` | 자동 추론이 어려운 멀티 소스 확장의 source 이름·기본 URL·versionId 관리 |

**예시**

```bash
python3 scripts/list_extensions.py --gradle-tasks --extensions "xtoon ntk"
python3 scripts/update_repo.py --deploy-dir ../Korean-Mihon-Extensions --extensions "xtoon ntk"
```

---

## 🔐 서명

릴리즈 APK를 같은 패키지명으로 계속 업데이트하려면 동일한 release 키가 필요합니다.

```text
SHA-256: b25af02d178fad20ebe739e59336f2ae5e307dcd1375418278e752dba03497cb
```

> `signingkey.jks`와 `signing.env`는 Git에 포함하지 않고 별도로 안전하게 보관합니다.

---

## ⚠️ 주의

- 로그인, 결제, 성인 인증, 캡차, 차단 우회, DRM 우회는 **구현하지 않습니다.**
- 공개된 HTML에서 바로 확인 가능한 정보만 파싱합니다.
- 콘텐츠 저작권은 각 원저작자 및 해당 사이트에 있으며, 이용에 대한 책임은 사용자 본인에게 있습니다.

---

## 📄 라이선스

이 저장소는 [Apache License 2.0](LICENSE)을 따릅니다.

<div align="center">

---

Made with ❤️ for the Korean Mihon community

</div>
