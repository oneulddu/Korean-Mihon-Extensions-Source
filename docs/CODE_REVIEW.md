# 코드 검토 및 개선 제안

> 작성일: 2026-06-08
> 대상: `Korean-Mihon-Extensions-Source` (소스) + `Korean-Mihon-Extensions` (배포)
> 범위: 빌드 구성 · 자동화 스크립트 · CI 워크플로 · 확장 코드 · 저장소 위생

전반적으로 Keiyoushi `extensions-source` 구조를 잘 따르고 있고, 자동 빌드 → 배포 레포 푸시 파이프라인이 깔끔하게 구성되어 있습니다. 아래는 우선순위별 개선 제안입니다.

---

## 🔴 High — 우선 처리 권장

### 1. 배포 README / index.html 자동 덮어쓰기 ⚠️

`scripts/update_repo.py`의 `write_readme()`와 `write_index_html()`가 **매 배포마다 고정 템플릿으로 배포 레포의 `README.md`와 `index.html`을 다시 생성**합니다. 그리고 워크플로의 커밋 단계에서 두 파일을 함께 `git add`합니다.

```python
# scripts/update_repo.py
write_readme(deploy_dir, sorted_legacy)      # README.md 통째로 덮어씀
write_index_html(deploy_dir)                 # index.html 통째로 덮어씀
```

```yaml
# .github/workflows/build_extensions_release.yml
git add README.md index.html index.json index.min.json repo.json apk icon
```

**영향**: 최근 배포 레포에 새로 작성한 README(배지·확장 표 포함)가 **다음 자동 빌드 시 옛 평문 템플릿으로 되돌아갑니다.** 실제로 직전 작업에서 rebase 도중 README 충돌이 났던 원인이기도 합니다.

**해결 옵션 (택1)**
- **(A) 권장** `write_readme()` / `write_index_html()` 호출을 제거하고, README·index.html은 배포 레포에서 수동 관리. 워크플로 커밋 단계의 `git add`에서도 두 파일 제외.
- **(B)** 템플릿 자체를 새 디자인(배지·표)으로 교체해 자동 생성 결과가 곧 최신 디자인이 되도록 유지. (확장 목록이 자동 갱신되는 장점은 유지됨)

> 참고: (B)를 선택하면 README의 "제공 확장" 표를 자동 생성할 수 있어 수동 갱신 부담이 사라집니다. 디자인 일관성과 자동화를 모두 원하면 (B)가 더 낫습니다.

---

## 🟠 Medium — 품질·일관성

### 2. 확장 디렉터리 레이아웃 불일치
대부분 확장은 소스를 `src/<...>/` 평면 구조에 두는데, **`ntk`만 `src/main/kotlin/<...>/`** 레이아웃을 사용합니다.

```
src/ko/blacktoon/src/eu/kanade/.../BlackToon.kt   ← 평면
src/ko/ntk/src/main/kotlin/eu/kanade/.../NTK*.kt  ← 다름
```
동작에는 문제 없으나(스크립트가 `src/**/*.kt`로 글롭) 유지보수 일관성을 위해 한쪽으로 통일 권장.

### 3. `build.gradle` 포맷 불일치
`src/ko/ntk/build.gradle`만 `apply plugin` 앞 빈 줄이 없는 등 사소한 포맷 차이가 있습니다. 루트에 spotless가 구성돼 있으니 `./gradlew spotlessApply`로 일괄 정리 가능.

### 4. PR/푸시 시 린트 검사 워크플로 부재
현재 워크플로는 빌드·배포(`build_extensions_release.yml`) 하나뿐입니다. 코드 스타일 회귀를 막기 위해 **`spotlessCheck`(+ ktlint)** 를 실행하는 경량 CI 워크플로 추가를 권장합니다. (PR 트리거, 빌드 없이 검사만)

### 5. 자동화 스크립트 테스트 부재
`scripts/list_extensions.py`, `scripts/update_repo.py`는 배포 파이프라인의 핵심인데 단위 테스트가 없습니다. APK 파일명 파싱(`APK_RE`), 버전 코드 추출, source id 생성(`generate_source_id`), 인덱스 병합 로직은 회귀 위험이 큽니다. `pytest` 기반 최소 테스트 추가 권장.

> 참고: `core/src/test/kotlin/keiyoushi/utils/NextJsTest.kt`처럼 Kotlin 쪽 테스트 인프라는 이미 존재합니다.

---

## 🟡 Low — 있으면 좋은 것

### 6. 빌드 상태 배지
소스 레포 README에 GitHub Actions 빌드 상태 배지를 추가하면 파이프라인 상태를 한눈에 볼 수 있습니다.

```markdown
![Build](https://github.com/oneulddu/Korean-Mihon-Extensions-Source/actions/workflows/build_extensions_release.yml/badge.svg)
```

### 7. 의존성 자동 업데이트
`dependabot.yml` 또는 Renovate를 추가하면 Gradle/플러그인 버전 업데이트를 자동 PR로 받을 수 있습니다.

### 8. `infer_sources()` 데드 경로화
현재 모든 확장이 `scripts/extensions.json`에 source가 정의돼 있어, `update_repo.py`의 `infer_sources()`(Kotlin 정규식 파싱) 경로는 사실상 실행되지 않습니다. 유지 시 혼동 가능 — 의도적 폴백이면 주석으로 명시, 불필요하면 단순화 검토.

### 9. 기여/이슈 가이드 부재
`.github/ISSUE_TEMPLATE`, `CONTRIBUTING.md`가 없습니다. 외부 기여를 받을 계획이면 추가 권장(개인 운영이면 불필요).

---

## ✅ 잘 되어 있는 점
- 민감 파일(`signingkey.jks`, `signing.env`, `local.properties`)이 `.gitignore`로 제외되어 있고, git 추적 목록에도 없음 — **양호**.
- 워크플로 `concurrency` 그룹으로 동시 배포 충돌 방지.
- 변경된 확장만 선별 빌드(`list_extensions.py --changed-from/--changed-to`)하여 CI 효율적.
- `CI_SELECTED_EXTENSIONS`로 빌드 대상 모듈만 Gradle 프로젝트에 포함시켜 빌드 시간 단축.
- 배포 커밋 메시지에 소스 커밋·워크플로 링크를 담아 추적성 우수.
- Wolf.com 도메인 번호 자동 갱신 태스크 등 사이트 변동 대응이 견고함.
- 배포 인덱스가 구형(`index.min.json`)/신형(`index.json`) 모두 지원.

---

## 📌 권장 처리 순서
1. **(High #1)** 배포 README/index.html 자동 덮어쓰기 정리 — 현재 새 README가 다음 빌드에 사라지므로 최우선.
2. **(Medium #4, #3)** 린트 CI 추가 + `spotlessApply`로 포맷 통일.
3. **(Medium #2)** `ntk` 디렉터리 레이아웃 통일.
4. **(Medium #5)** 스크립트 `pytest` 최소 테스트.
5. **(Low)** 배지 · dependabot 등 점진 적용.
