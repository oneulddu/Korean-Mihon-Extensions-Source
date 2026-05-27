# Xtoon Mihon Extension Source

`https://t3.xtoon365.com` 공개 페이지를 대상으로 하는 Mihon/Tachiyomi 확장 소스 레포입니다.

배포 전용 레포는 아래에 따로 둡니다.

```text
https://github.com/oneulddu/Xtoon-Mihon-Extension
```

## 빌드

Java와 Android SDK가 필요합니다.

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :src:ko:xtoon:assembleRelease
```

## 서명

릴리즈 APK를 같은 패키지명으로 계속 업데이트하려면 같은 release 키가 필요합니다.

`signingkey.jks`와 `signing.env`는 Git에 올리지 않습니다.

현재 배포 APK 서명 키 지문:

```text
SHA-256: 62aaff9a192e8d3e462b352a4b435bcacc38c2390aa9c0dbf9c863942401adf0
```

## 현재 구현

- 최신 목록: `/category/theme/300/order/addtime`
- 인기 목록: `/category/theme/300/order/hits`
- 검색: `/index.php/search?key=...`
- 상세 정보: `/comic/{id}`
- 회차 목록: 상세 페이지의 `.chapter-list a[href^=/chapter/]`
- 이미지 목록: `/chapter/{id}` 페이지의 `img.lazy-read[data-original]`
- 분류 필터: 일반웹툰, BL&GL, 성인웹툰
- 상태 필터: 전체, 연재중, 완결
- 요일 필터: 월, 화, 수, 목, 금, 토, 일
- 장르 필터: 사이트에 노출된 태그 100개
- 연결 클라이언트: Mihon/Tachiyomi `cloudflareClient` 기반, HTML 요청용 `Accept` 헤더 보정

## 주의

로그인, 결제, 성인 인증, 캡차, 차단 우회, DRM 우회는 구현하지 않습니다. 공개 HTML에서 바로 확인 가능한 정보만 파싱합니다.
