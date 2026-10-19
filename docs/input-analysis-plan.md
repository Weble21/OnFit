# OnFit 공고 입력형 맞춤 분석 전환 계획

기준일: 2026-10-18. 1차 작업은 **수정 방안 정리 + 폐기 기능 삭제**였고,
같은 날 2차 작업으로 아래 1~5단계를 구현했다(LLM: Anthropic Claude API, 기본 모델 `claude-opus-5-5`).
구현 결과와 남은 일은 하단 [구현 상태](#구현-상태-2026-10-18)를 기준으로 한다.

## 근거와 우선순위

1. 사용자의 이번 결정과 [AI Agent 구조](https://app.notion.com/p/3f23facfac0780c19f2bfd5a7cb19d1f)의 2026-10-18 방향을 적용한다.
2. [프로젝트 기록](https://app.notion.com/p/3e93facfac07811c8f84caa0af1be324),
   [파일 구조와 데이터 흐름](https://app.notion.com/p/3eb3facfac078169b5f7c1a497feadcb),
   [공고 수집 문제점](https://app.notion.com/p/3f73facfac07806e96cdcf7e33b8168a)도 확인했다.
   과거 페이지에는 자동 추천, 수집, 알림, 인증 미구현 등 오래된 내용이 남아 있다.
   이 내용은 현재 요구사항이나 현재 코드 상태로 취급하지 않는다. 이번 작업에서 Notion 원문은 변경하지 않았다.

서비스 정의: **내가 관심 있는 공고를 넣으면 내 프로필과 얼마나 맞는지 분석해주는 서비스.**

## 코드에서 확인한 현재 상태

| 영역 | 현재 구현 | 필요한 변경 |
| --- | --- | --- |
| 로그인·프로필 | Keycloak/OIDC, 사용자별 프로필 CRUD, 기술·프로젝트·경력·자격증 | 새 분석 결과에도 동일 소유권 적용 |
| 파일 입력 | `JobTextExtractionService`, PDFBox, PaddleOCR/Tesseract | 기존 구현 재사용; 분석 API와 계약 통일 |
| 확인·수정 | `useJobAnalysis`에서 파일 텍스트 확인 후 분석, 수정 시 확인 취소 | 서버에서도 확인된 원문 버전만 분석하도록 보장 |
| 텍스트 분석 | `data.js`의 기술명 키워드 비교 데모 | AI 구조화 + 서버 점수 계산으로 교체 |
| 맞춤 계산 | `RecommendationCalculator`, 기술 정규화, 점수·근거 엔티티 | 입력한 단일 공고를 전달하는 서비스 추가 |
| 의미 유사도 | 별도 FastAPI 서비스와 `HttpSemanticScoreProvider` | 선택 기능으로 재사용, 모델 버전 기록 |
| 직무 분석 | 없음 | 요약·업무·역량·키워드·강점·부족·강조 경험 구현 |
| 분석 저장 | 기존 Recommendation/JobPosting은 공용 공고 중심 | 사용자 소유 원문·구조화·프로필 스냅샷 모델 추가 |

현재 화면의 기술 일치율은 서버 맞춤 점수가 아니다. AI 구조화·직무 분석을 구현하기 전까지
화면의 **키워드 비교 데모** 표기를 유지한다. AI 서비스의 의미 유사도 기능도 LLM 직무 분석과 구분한다.

## 이번에 제거한 범위

- `collector/`: 사람인·원티드·잡코리아 수집·조회 및 전용 테스트
- 백엔드 `ingestion/`: 고용24, 수집 정책·상태·관리자 import/이력 API
- 백엔드 `alerts/`: 이메일 인증·구독·발송·배치·발송 제한, SMTP 의존성과 환경설정
- `.github/workflows/job-alerts.yml`, CI 수집기 작업, 수집/알림 전용 문서
- 공고 목록/상세 API, 전체 공고 자동 추천 API와 해당 서비스/응답 DTO
- 프론트 추천·관심 기업·알림 화면, 상세 모달, 관련 API 함수와 기업 그룹 필터
- 프로필의 브라우저 전용 관심 업종·기업 입력
- 앱 시작 시 가상 공고 자동 적재; 가상 공고 JSON은 **테스트 리소스**로 이동
- 외부 수집 전용 공고 생성·수정 메서드

로그인과 프로필, OCR, 점수 계산기, 의미 유사도, 점수 저장 엔티티, 평가 데이터와
운영 백업/복원/health/로그 기능은 유지한다. 운영 장애 알림은 사용자 공고 이메일과 별개다.

V1~V12 Flyway 파일은 체크섬과 업그레이드 이력을 위해 그대로 둔다.
기존 공고·추천·수집·알림 DB 데이터는 삭제하지 않았다. 쓰이지 않는 테이블/출처 열은
새 분석 모델 이관 이후 별도 forward migration으로 정리한다. 기존 결과 만료 정리 정책은 유지한다.

## 권장 구현 순서와 완료 조건

### 1. 분석 저장 모델과 API 계약

`job-analysis/`를 신설하고 `JobAnalysis`에 사용자 ID, 입력 종류(TEXT/FILE), 확인 원문,
원문 해시, 구조화 결과, 공고/프로필 버전, 점수/근거, 직무 분석, 모델/프롬프트/계산기 버전,
상태와 생성 시각을 저장한다. 원본 파일은 현재처럼 처리 후 폐기하는 것을 기본으로 한다.
공고 원문과 개인 프로필을 전역 공고 목록으로 노출하지 않는다.

- `POST /api/job-analysis/file`: 추출 초안 반환. 업로드만으로 점수를 계산하지 않는다.
- `POST /api/job-analysis/text`: 직접 입력 또는 확인·수정된 추출 원문을 받아 분석한다.
  파일 초안 ID와 원문 버전/해시를 받아 오래된 확인 상태를 거부한다.
- `GET /api/job-analysis/{analysisId}`: 소유자만 결과 조회; 타 사용자에게 404.
- 기존 `POST /api/job-text/extract`는 새 파일 계약이 준비될 때 대체한다.

노션의 FastAPI `/internal/matches/calculate`는 **Spring 계산기를 호출하는 어댑터**로
해석한다. Python에서 같은 점수식을 다시 구현하지 않는다. 외부 입력의 user_id를 신뢰하지 않고
Spring의 인증 주체를 서버 내부 호출 문맥에 전달한다.

완료 조건: 계정 간 접근 차단, 파일 확인 전 분석 차단, 원문 변경 후 재확인,
반복 요청의 멱등성, 입력 길이/파일 형식/시간 제한, 실패 후 재시도.

### 2. 공고 구조화와 근거 검증

FastAPI `POST /internal/jobs/extract`에 확인된 원문을 전달한다.
회사명·제목·직무·담당업무·필수역량·우대역량·경력·자격·근무지를 JSON Schema로 검증한다.
각 항목에는 원문 인용/구간 근거를 포함하며, 없는 정보는 null/빈 배열로 둔다.
현재 JobPosting의 필수값 제약(회사/제목/직무/근무지 등)을 그대로 강요하면 정보를 지어내게 되므로
새 DTO와 저장 모델에서 미확인 값을 허용하고 계산 입력 어댑터에서 처리한다.

완료 조건: 필수/우대 구분, 원문에 없는 기술·경력 생성 차단, 모호한 조건 표시,
깨진 JSON/타임아웃/텍스트 내 지시문에 대한 검증, 동일 확인 원문 사용.

### 3. 기존 점수 계산기 연결

Spring에서 인증 프로필과 구조화된 단일 공고를 `RecommendationCalculator`에 전달한다.
총점·부분 점수·일치 역량·부족 역량·경험 근거를 하나의 결과로 저장한다.
AI 설명 생성은 저장된 점수에 접근만 하고 수정할 수 없도록 응답 계약을 분리한다.

현재 가중치나 결측치 처리 규칙은 임의로 바꾸지 않는다. 의미 유사도 비활성/실패 시의
점수 한계도 사용자에게 설명한다. 원문이 같아도 LLM 구조화나 임베딩 모델이 바뀌면
점수가 달라질 수 있으므로 구조화/프로필/모델 버전을 고정·저장하여 재현성을 확보한다.
의미 유사도 실패를 정상 점수로 숨기지 않고 계산 버전과 상태에 기록한다.

완료 조건: 같은 구조화 공고·프로필·모델 버전이면 같은 점수, 별칭 일치,
빈 요구사항에서 만점 생성 금지, 기존 평가 세트의 점수 회귀 검증.

### 4. 직무 분석과 근거 경험

`POST /internal/jobs/analyze-role`을 추가한다. 결과는 다음 7개 항목으로 고정한다.

1. 직무 한줄 요약
2. 핵심 업무
3. 핵심 요구 역량
4. 주요 직무 키워드
5. 내 강점
6. 프로필에서 확인되지 않은 역량
7. 지원 시 강조할 경험

공고 자체 정보는 공고 원문, 개인 강점/경험은 실제 프로필 항목 ID와 인용을 근거로 삼는다.
프로필 미기재는 실제 역량 부재와 구분한다. 설명 실패 시 점수 결과는 유지하고
직무 분석만 재시도할 수 있게 한다. 경력·성과·자격증을 생성하거나 점수를 덮어쓰지 않는다.

완료 조건: 7개 항목 표시, 근거 이동/확인, 없는 경험 생성 차단, 점수 불변,
분석 실패와 부분 성공의 명확한 표시.

### 5. Tool 경계·관측·UI 통합

| Tool | 구현 책임 | 입력/출력 원칙 |
| --- | --- | --- |
| `get_user_profile` | Spring 프로필 서비스 | 인증 주체에서 소유권 결정 |
| `extract_job_posting` | FastAPI Extractor/Validator | 원문 → 근거 포함 구조화 JSON |
| `calculate_match_score` | Spring 계산기 | 프로필 + 구조화 공고 → 결정론적 점수 |
| `compare_profile_with_job` | Spring 비교 결과 어댑터 | 점수와 일치/누락 근거 재사용 |
| `find_supporting_evidence` | 프로필 근거 조회 | 실제 프로젝트/경력 ID 및 인용 반환 |
| `analyze_job_role` | FastAPI 직무 분석 | 공고 요약 + 프로필 근거 기반 설명 |

처음에는 정해진 순서의 파이프라인으로 구현하고, 모델 라우터/fallback은 실패·복잡도 기준이
필요할 때 추가한다. `search_job_postings`와 외부 탐색 도구는 제공하지 않는다.
요청 ID, 모델/프롬프트 버전, 토큰, 처리시간, 오류 코드를 기록하며 원문·프로필·토큰은 로그에 남기지 않는다.

프론트는 입력 → 추출 확인 → 구조화 확인/분석 → 점수·직무 분석 순서로 정리한다.
데모 `analyzeText()`는 서버 분석 연결 완료 후 제거한다. 미구현 기능을 완성된 기능으로 표시하지 않는다.

완료 조건: PDF/스캔/이미지/텍스트 경로 통합, 파일 교체 중 요청 취소, 오래된 응답 무시,
프로필 변경 시 결과 만료, 키보드 접근 및 모바일 확인, 사용자 간 데이터 격리.

## 구현 상태 (2026-10-18)

| 단계 | 구현 위치 | 비고 |
| --- | --- | --- |
| 1. 저장 모델·API | `V13__job_analysis.sql`, `domain/JobAnalysis*`, `jobanalysis/JobAnalysisController·Service` | `POST /api/job-analysis/file`(초안), `POST /api/job-analysis/text`, `GET /api/job-analysis/{id}`, `POST /api/job-analysis/{id}/role-analysis`. `/api/job-text/extract`는 제거 |
| 2. 구조화·근거 검증 | `ai-service/job_llm.py` `extract_job_posting`, `ground_posting` | JSON Schema 구조화 출력. 원문에 없는 인용, 인용에 없는 기술명은 버림. 필수/우대 중복은 필수로, 모호한 조건은 `ambiguities` |
| 3. 계산기 연결 | `jobanalysis/JobMatchScorer` | 가중치·결측 규칙 변경 없음. 미확인 직무·지역은 빈 값(불일치), 의미 유사도 상태(OK/DISABLED/FAILED)와 최고점 저장 |
| 4. 직무 분석 | `job_llm.py` `analyze_role`, `ground_role` | 7개 항목 고정. 공고 인용·프로필 항목 ID+인용 검증, 키워드는 원문 표기만. 실패 시 점수 유지·재시도 |
| 5. 경계·관측·UI | `JobAiClient`, `useJobAnalysis`, `AnalyzePage` | 고정 순서 파이프라인. 요청 ID·모델·프롬프트 버전·토큰·처리시간·오류 코드만 로그. 데모 `analyzeText()` 제거 |

Tool 표의 책임 분리는 다음과 같이 반영했다. `get_user_profile`은 `CurrentUser`+`ProfileSnapshot`(Spring),
`extract_job_posting`·`analyze_job_role`은 ai-service 엔드포인트, `calculate_match_score`·`compare_profile_with_job`은
`JobMatchScorer`(Spring), `find_supporting_evidence`는 `ProfileSnapshot.evidenceItems()`와 응답의 `profileItems` 라벨이다.
ai-service 요청에는 사용자 ID가 없고, 점수는 Spring만 쓴다. FastAPI `/internal/matches/calculate` 어댑터는 Python이
점수를 호출할 필요가 생기지 않아 만들지 않았다. 모델 라우터/fallback은 API의 `fallbacks: "default"`(정책 거절 시 서버 측 대체 모델)만 쓴다.

멱등성은 (사용자, 원문 해시, 프로필 스냅샷 해시, 계산기 버전) 기준으로 저장된 결과를 돌려주는 방식이다.
동시에 들어온 같은 요청 두 개는 각각 저장될 수 있다.

남은 일:

- 실제 Claude 호출로 구조화·직무 분석 품질을 재는 평가 세트(필수/우대 구분 정확도, 환각 비율, 지시문 주입 사례)
- 한글로만 적힌 기술명(예: "스프링 부트")은 원문 표기를 그대로 쓰므로 영문 프로필 기술과 별칭 일치하지 않는다
- `frontend/tests/browser-smoke.mjs`는 이미 제거된 화면(`/favorites`, `/recommendations`)을 참조하는 이전 상태 그대로다
- 쓰이지 않는 공고·추천·수집·알림 테이블 정리 forward migration

## 검증 기록

검증 실행 결과는 이 문서 하단에 기록한다. PostgreSQL 검증은 테스트 전용 컨테이너를 사용한다.

2026-10-18 (Windows 로컬, Docker 29.5.3):

- `backend`: `gradlew test` 통과(신규 `JobAnalysisApiTests` 7개, `JobAiClientTests` 1개 포함, OCR 환경 의존 2개 skip),
  `gradlew postgresTest` 14개 통과(새 DB와 V1~V12 각 버전에서 V13까지 업그레이드, Hibernate validate)
- `ai-service`: `pytest test_app.py test_job_llm.py` 13개 통과(Python 3.10, 가짜 Claude 클라이언트. Anthropic API 미호출)
- `frontend`: `npm test` 20개 통과, `npm run check` 빌드 성공
- 실제 Anthropic API 호출과 브라우저 수동 확인은 하지 않았다.
