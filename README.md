# Korean Mihon Extensions Source

한국어 Mihon/Tachiyomi 확장을 모아 수정하기 위한 소스 레포입니다.

배포 전용 레포는 아래에 따로 둡니다.

```text
https://github.com/oneulddu/Korean-Mihon-Extensions
```


## 포함한 한국어 확장

Keiyoushi `extensions-source` PR #15649 기준 한국어 확장들을 함께 가져왔습니다.

```text
blacktoon
manatoki
navercomic
ntk
rawdex
toon11
toonkor
wolfdotcom
xtoon
```

`newtoki` 이름으로 남아 있는 최종 디렉터리는 없고, 해당 PR 최종 상태에서는 `ntk` 패키지로 정리되어 있습니다.


## 자동 빌드/배포

`.github/workflows/build_extensions_release.yml`가 한국어 확장들을 빌드하고 `oneulddu/Korean-Mihon-Extensions`의 `repo` 브랜치에 APK와 인덱스를 갱신합니다.

필요한 GitHub Secrets:

```text
SIGNING_KEYSTORE_BASE64  # signingkey.jks를 base64 인코딩한 값
KEY_STORE_PASSWORD
ALIAS
KEY_PASSWORD
DEPLOY_TOKEN             # 배포 레포 contents write 권한이 있는 토큰
```

서명키와 토큰은 민감 정보라 레포에는 넣지 않습니다.

## 빌드

Java와 Android SDK가 필요합니다.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew $(python3 scripts/list_extensions.py --gradle-tasks --extensions "xtoon ntk")
```

## 서명

릴리즈 APK를 같은 패키지명으로 계속 업데이트하려면 같은 release 키가 필요합니다.

`signingkey.jks`와 `signing.env`는 Git에 올리지 않습니다.

현재 배포 APK 서명 키 지문:

```text
SHA-256: 62aaff9a192e8d3e462b352a4b435bcacc38c2390aa9c0dbf9c863942401adf0
```

## 자동 배포 스크립트 구조

- `scripts/list_extensions.py`: 빌드할 확장 모듈을 찾고 Gradle task 목록을 만듭니다.
- `scripts/update_repo.py`: 빌드된 APK들을 `index.json`, `index.min.json`, `repo.json`에 반영합니다.
- `scripts/extensions.json`: 자동 추론이 어려운 멀티 소스 확장의 source 이름, 기본 URL, versionId를 관리합니다.

예시:

```bash
python3 scripts/list_extensions.py --gradle-tasks --extensions "xtoon ntk"
python3 scripts/update_repo.py --deploy-dir ../Korean-Mihon-Extensions --extensions "xtoon ntk"
```

## 주의

로그인, 결제, 성인 인증, 캡차, 차단 우회, DRM 우회는 구현하지 않습니다. 공개 HTML에서 바로 확인 가능한 정보만 파싱합니다.
