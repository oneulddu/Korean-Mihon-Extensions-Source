# 굿툰 확장

- 모듈: `src/ko/goodtoon`, 패키지: `eu.kanade.tachiyomi.extension.ko.goodtoon`
- Mihon 소스 이름: `굿툰`, 언어: `ko`, 최초 버전: `1.4.1`
- 기본 주소: `https://www.goodtoon003.com`
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
