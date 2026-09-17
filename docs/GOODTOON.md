# 굿툰 확장

- 모듈: `src/ko/goodtoon`, 패키지: `eu.kanade.tachiyomi.extension.ko.goodtoon`
- Mihon 소스 이름: `굿툰`, 언어: `ko`, 최초 버전: `1.4.1`
- 기본 주소: `https://www.goodtoon004.com`
- 성인 콘텐츠가 함께 제공되는 사이트이므로 확장의 18+ 표시를 사용한다.

## 제공 기능

기본 목록과 최신 업데이트, 검색, 페이지네이션, 연재·완결 및 분류·요일·장르·플랫폼 필터를 제공한다. 사이트에 독립된 인기 정렬이 없어 Mihon의 Popular 탭도 기본 업데이트 목록을 보여준다. 장르와 플랫폼 필터에는 주요 항목을 제공한다.

작품·회차 URL은 서버가 제공한 경로를 저장해 도메인 변경 후에도 사용할 수 있다. 상세의 작가 정보는 사이트가 모바일 HTML에서 생략하므로 상세 요청에서 Android·Mobile User-Agent 표기를 데스크톱 표기로 바꾼다. 그 외 요청의 사용자 에이전트는 Mihon 설정을 따른다.

회차는 `POST /manga/gt-{id}/ajax/chapters`의 전체 목록을 사용한다. Referer는 작품 상세 URL이며 `X-Requested-With: XMLHttpRequest`를 전송한다. 별도 로그인·토큰·nonce는 필요하지 않았다. 사이트의 더보기 버튼은 이미 응답에 포함된 회차를 펼치는 UI다. 숫자 슬러그와 `chapter-N` 슬러그를 모두 반환된 링크에서 읽으며 빈 응답, 중복 링크, 다른 작품의 회차는 오류로 처리한다.

일부 제목에는 `0427원존용의비상 426화`처럼 회차와 다른 숫자 접두사가 있어 마지막 `N화` 표기를 회차 번호로 지정한다. 원래 제목과 회차 경로는 보존한다. 뷰어는 `.reading-content img.wp-manga-chapter-img`의 `data-src`, `src` 순으로 주소를 읽고, 페이지 순서를 보존한다.

## 주소 갱신

사이트의 최신 주소 메뉴가 연결하는 공식 채널 `https://t.me/goodtoon_url`의 공개 HTML `https://t.me/s/goodtoon_url`을 확인한다. 자체 게시물을 최신 게시물 번호부터 읽고 전달된 게시물이나 다른 채널의 링크는 사용하지 않는다. 2026-09-09 확인한 17번 게시물은 `http://goodtoon003.com/`을 안내한다. 해당 호스트의 기본 포트·루트 경로·사용자 정보 및 쿼리 없음 등을 먼저 검사한 뒤 HTTPS로 바꾸고 다시 검증한다. HTTP 콘텐츠 요청은 보내지 않는다.

우선순위는 수동 HTTPS 전체 URL → 12시간 자동 캐시 → 공식 채널 → 현재 콘텐츠 주소의 리다이렉트 → 만료된 마지막 정상 캐시 → 빌드 기본 주소다. 실패 후 재시도 제한은 15분이며 조회 클라이언트는 요청당 8초 제한과 자동 리다이렉트 금지를 사용한다. 공통 `DynamicBaseUrlResolver`가 동시 탐색을 합친다.

자동 호스트는 `goodtoon` + 숫자 + `.com` 및 해당 호스트의 `www` 형태만 허용한다. 수동 주소가 있으면 자동 결과로 덮어쓰지 않는다. `img.goodtoon9001.top` 등 이미지 CDN 요청은 재작성하지 않으며 뷰어 이미지 요청에는 실제 뷰어 주소의 Referer와 Origin을 보낸다. 새 소스이므로 이전 설정 마이그레이션 대상은 없다.

## 회귀 검사

`GoodToonParserTest`는 실제 응답에서 필요한 마크업만 추린 fixture로 목록과 다음 페이지, 상세, 26개 회차, 108개 이미지, 주소 검증과 CDN 보존을 검사한다. 빈 검색 결과와 차단·구조 변경 응답을 구분하고, 잘못된 회차 숫자 접두사에 대한 회귀 검사를 포함한다. 공유 주소 캐시·재시도·동시성 동작은 core의 기존 테스트를 사용한다.

```bash
./gradlew --no-parallel spotlessCheck
./gradlew :src:ko:goodtoon:testDebugUnitTest :src:ko:goodtoon:assembleRelease -x spotlessCheck
```

원본 웹툰 이미지와 광고 스크립트는 테스트 fixture에 포함하지 않는다. 아이콘은 사이트가 제공한 로고를 크기별로 축소해 사용한다.

## 2026-09-09 검증 결과

기존 `ntk-runtime-api35` Android 에뮬레이터의 실제 Mihon에서 서명된 1.4.1 APK를 설치하고 신뢰했다. 굿툰 소스 노출, 기본·최신 목록, 한글 검색, 상세의 제목·작가·표지·설명, 회차 목록과 뷰어 이미지를 확인했다.

- `덕혜옹주를 도와줘`: 사이트와 Mihon 모두 26개 회차. 작가 `홍인표,주먹` 표시. 26화 뷰어는 108페이지로 표시되고 이미지가 로드됐다.
- `원존: 용의 비상`: 사이트 AJAX와 Mihon 모두 618개 회차. 수정 APK로 새로고침한 뒤 `Missing chapters` 표시 없음. 618화 뷰어는 66페이지로 표시되고 이미지가 로드됐다.
- 수동 `https://www.goodtoon003.com`으로 최신 목록을 조회했다. 유효한 형태지만 존재하지 않는 수동 호스트 `https://goodtoon.invalid`를 설정하면 해당 호스트의 DNS 오류를 표시하며 자동 주소로 덮어쓰지 않았다. 값을 비운 뒤 `https://goodtoon003.com`, 탐색 출처 `공식 텔레그램 주소 안내`로 복귀했다.
- 전체 Kotlin 테스트 147개(굿툰 14개 포함), Python 테스트 12개, JavaScript 테스트 2개와 루트 Spotless 검사가 통과했다. 릴리스 APK 빌드와 선택 배포 미리보기에서 기존 9개 인덱스 항목의 내용이 보존되는 것을 확인했다.

로컬 화면 증거: `/tmp/goodtoon-mihon-detail-final.png`, `/tmp/goodtoon-mihon-long-final.png`, `/tmp/goodtoon-mihon-viewer.png`, `/tmp/goodtoon-mihon-long-viewer-final.png`, `/tmp/goodtoon-mihon-auto-settings.png`. 이 기록은 대표 작품의 검사이며 모든 작품·모든 페이지의 로딩을 보증하지 않는다.

최종 독립 코드 리뷰에서 이전 수동 주소의 Referer가 남는 경우를 발견해 수정했다. 사이트 요청은 최종 주소로 Referer·Origin을 함께 바꾸고 외부 CDN 요청은 그대로 둔다. 해당 회귀 테스트를 추가하고 변경 모듈의 테스트·빌드를 다시 실행했다.

## 배포 결과

[GitHub Actions 34394080904](https://github.com/oneulddu/Korean-Mihon-Extensions-Source/actions/runs/34394080904)가 소스 커밋 `354ac74c79d748a9093943f59e510068cd04bc3f`의 굿툰만 빌드·배포했다. 배포 저장소 `repo` 브랜치의 커밋은 `1261ad15c77167da28ec37be0d5f8a9a4e761dd9`다.

공개 인덱스는 10개 확장·15개 소스로 확인됐고 기존 9개 확장의 항목은 배포 전과 동일했다. 새 소스 ID는 `1065455645154575522`다. 공개 APK를 다시 다운로드해 패키지명, 버전 1.4.1, versionCode 1과 기존 배포 서명 인증서가 일치하는지 검사했다.

- [굿툰 1.4.1 APK](https://raw.githubusercontent.com/oneulddu/Korean-Mihon-Extensions/repo/apk/tachiyomi-ko.goodtoon-v1.4.1-release.apk)
- APK SHA-256: `64cec68715bd70bc945c1ecfdcab9a06c98f8192d43f9c0ae8e9d9748ef07a35`
- 서명 인증서 SHA-256: `b25af02d178fad20ebe739e59336f2ae5e307dcd1375418278e752dba03497cb`


## 2026-09-18 표지 요청 헤더 수정

Mihon의 `HttpSource.headers`는 처음 생성한 값을 재사용하므로 도메인이 자동 갱신되거나 수동 주소가 바뀌어도 CDN 표지 요청에 이전 Referer가 남을 수 있었다. 굿툰이 실제 사용하는 `img.goodtoon9001.top` 요청은 이미지 URL을 유지하고 Referer·Origin만 현재 접속 주소로 갱신한다. 뷰어 이미지의 Referer 경로도 보존하며 다른 외부 호스트 요청은 변경하지 않는다. 기본 콘텐츠 주소는 공식 채널의 `goodtoon004.com`으로 갱신했다. 버전은 1.4.2다.

수정 전 현재 목록의 93개 CDN 표지와 대표 화면은 정상 로드되어, 사용자가 겪은 특정 작품의 썸네일 실패까지 재현했다고 판단하지 않았다. JavaScript `onerror` 대체 표지 처리는 이번 변경에 추가하지 않았다. 확인된 오래된 헤더 문제를 수정했고 전체 썸네일 문제가 해결됐다고 확대해서 보고하지 않는다.

기존 API 35 에뮬레이터의 실제 Mihon에서 서명된 수정 APK로 목록 표지와 작품 상세·회차·뷰어 이미지 표시를 확인했다.

- `악역 플레이어는 강해지고 싶다`: 사이트 AJAX와 Mihon 모두 871개 회차, `Missing chapters` 없음. 871화 뷰어 73페이지 중 실제 이미지 표시 확인.
- `차원이동 레벨업`: 사이트 AJAX와 Mihon 모두 469개 회차. 원본 목록에 393화가 없어 `Missing 1 chapter`가 표시된다. 이번 변경의 누락이 아니므로 회차를 임의 생성하지 않았다. 470화 뷰어 99페이지 중 실제 이미지 표시 확인.
- 목록 표지 및 뷰어 이미지 요청에서 CDN 주소 보존·현재 Referer/Origin·이전 수동 주소 처리·무관한 외부 호스트 보존 회귀 검사를 통과했다.

화면 증거는 `/tmp/mihon-fixes-0918/goodtoon-after.png`, `goodtoon-detail-final.png`, `goodtoon-viewer-final.png`, `goodtoon-470-viewer.png`에 보관했다. 전체 Spotless, Kotlin 162개(로컬 짭툰 보류 수정과 리뷰 후 추가한 공통 코드 회귀 2개 포함), Python 12개, JavaScript 2개가 통과했다. Astra 독립 리뷰에서 발견한 주소 복구 재시도·본문 읽기 오류 처리 두 건도 수정하고 해당 검사를 다시 통과했다.


### 1.4.2 공개 배포 완료

[PR #29](https://github.com/oneulddu/Korean-Mihon-Extensions-Source/pull/29)로 main에 반영한 뒤 [자동 배포 35271782999](https://github.com/oneulddu/Korean-Mihon-Extensions-Source/actions/runs/35271782999)가 성공했다. 공개 인덱스와 [굿툰 1.4.2 APK](https://raw.githubusercontent.com/oneulddu/Korean-Mihon-Extensions/repo/apk/tachiyomi-ko.goodtoon-v1.4.2-release.apk)의 버전·서명을 직접 확인했다. 커밋과 SHA-256 및 다른 확장 보존 결과는 [주소 복구 배포 기록](ADDRESS_DISCOVERY.md)에 남겼다.
