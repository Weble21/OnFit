# OnFit Backend

Spring Boot API server for OnFit job posting analysis. Project overview: [docs/README.md](../docs/README.md).

## Stack

- Java 21
- Spring Boot 4
- Spring Web MVC
- Spring Data JPA
- PostgreSQL
- Flyway
- Gradle

## Run

Create `.env` in the repository root with `ONFIT_KC_ADMIN_PASSWORD`, then start
PostgreSQL and Keycloak from that directory:

```powershell
docker compose --env-file .env -f infra/compose.yaml up -d
docker compose --env-file .env -f infra/compose.auth.yaml up -d
```

The Compose file uses `onfit` / `onfit_local` for local development. Override
`DB_PASSWORD` (and `DB_PORT` if needed) before starting the container. The
backend accepts `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` environment variables.
If you change `DB_PORT`, set `DB_URL` to the matching host port as well.

Then run from the `backend` directory:

```powershell
./gradlew.bat bootRun
```

In Git Bash, use `./gradlew bootRun`. The default profile uses the local
Keycloak issuer (`http://localhost:8081/realms/onfit`) and API audience
(`onfit-api`). Start Keycloak first and leave port 8080 free. Override
`ONFIT_AUTH_ISSUER` and `ONFIT_AUTH_AUDIENCE` when using another realm. For
local `bootRun`, `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, and those two auth
settings can be read from the ignored root `.env`; shell variables take precedence.
Keycloak admin and social provider secrets are not passed to the backend process.

Flyway creates the schema on startup. Hibernate validates it and does not alter
tables automatically. PostgreSQL must be running for the API to start.

## 검증

```powershell
./gradlew.bat test recommendationEvaluation
# Docker 필요: 새 DB와 V1~V12 업그레이드, 프로필 API, 백업/복원 검증
./gradlew.bat postgresTest
```

가상 공고 40건은 `src/test/resources/seed/job-postings.json`의 점수 평가용 데이터입니다.
앱 시작 시 공고를 자동 적재하지 않습니다.

## PostgreSQL 상태 확인

저장소 루트에서 다음 명령을 실행합니다.

```powershell
docker compose -f infra/compose.yaml ps
docker compose -f infra/compose.yaml exec postgres pg_isready -U onfit -d onfit
docker compose -f infra/compose.yaml exec postgres psql -U onfit -d onfit
```

`service "postgres" is not running`이 나오면 기존 컨테이너를 재시작합니다.
데이터는 Compose 볼륨에 유지됩니다.

```powershell
docker compose -f infra/compose.yaml start postgres
```

컨테이너가 아직 없다면 `docker compose -f infra/compose.yaml up -d`를 실행합니다.

## 프로필 API (Phase 1)

프로필은 Keycloak/OIDC 토큰의 인증 주체별로 분리됩니다. `test` 프로필에서만
`demo@onfit.local`을 사용합니다. 로컬 실행도 유효한 Bearer 토큰이 필요합니다.

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| POST | `/api/profiles` | 프로필 생성 (201, 이미 있으면 409) |
| GET | `/api/profiles/me` | 내 프로필 조회 (없으면 404) |
| PUT | `/api/profiles/me` | 내 프로필 전체 교체 (없으면 404) |

백엔드를 실행한 상태에서 PowerShell로 확인합니다.

```powershell
$headers = @{ Authorization = 'Bearer <access_token>' }
$profileBody = @{
  targetRoles = @('백엔드 개발자')
  preferredLocations = @('서울')
  skills = @('Java', 'Spring Boot', 'PostgreSQL', 'AWS', 'Docker')
  certificates = @(@{ name = '정보처리기사'; category = 'TECHNICAL' })
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Method Post -Headers $headers -Uri 'http://localhost:8080/api/profiles' -ContentType 'application/json; charset=utf-8' -Body $profileBody
Invoke-RestMethod -Method Get -Headers $headers -Uri 'http://localhost:8080/api/profiles/me'
```

`PUT /api/profiles/me`에도 같은 JSON 형식을 보내며, 전달한 목록이 기존 목록을
대체합니다. `targetRoles`는 1개 이상 필요하고 `preferredLocations`는 빈 목록을
허용합니다. `projects`와 `experiences`는 선택 사항이고 생략하면 빈 목록으로
저장합니다. 기술명은 공백과 대소문자를 정리하고 `SpringBoot` → `spring boot`,
`k8s` → `kubernetes` 같은 별칭을 통일합니다. 별칭 표는
`src/main/resources/skill-aliases.json`이며 프론트엔드 사본과 테스트로 동기화를 확인합니다.
정규화 후 중복이면 400을 반환합니다.

오류 응답은 RFC 9457 `ProblemDetail` 형식이며 `detail`에 사용자에게 보여줄 한국어
문구가 담깁니다(예: `중복된 기술입니다: Amazon Web Services`).

## 추천 점수 계산기 (5단계)

`RecommendationCalculator`는 프로필과 공고를 받아 0~100점의 부분 점수 및
추천 근거를 계산합니다. 전체 점수는 `필수 기술 × 0.35 + 우대 기술 × 0.20 +
의미 유사도 × 0.20 + 경험 연관성 × 0.15 + 희망조건 × 0.10`이며 소수 둘째 자리로
반올림합니다. 기본값(`ONFIT_AI_ENABLED=false`) 또는 AI 장애 대체 시 의미 유사도는 0점이므로 최고점은 80점입니다.
AI 연동을 켜고 정상 응답을 받으면 의미 유사도에 따라 최고 100점까지 계산합니다.

- 필수·우대 기술: 정규화한 기술명의 일치 비율. 공고에 해당 기술 목록이 없으면 0점.
- 경험 연관성: 공고 직무와 정확히 일치하는 경력이 있으면 100점. 없으면 프로젝트별
  `techStack`을 쉼표(전각 포함)·세미콜론·줄바꿈으로 나누어(`CI/CD`처럼 `/`가 든 이름은 유지) 필수 기술을 얼마나 포함하는지 계산하고
  가장 높은 비율을 사용합니다. 둘 다 없으면 0점.
- 희망조건: 희망 직무 일치 50점, 희망 지역 일치 50점. 값이 없거나 불일치하면 0점.
  지역은 시·도 단위로 비교해 `서울특별시 강남구`도 `서울` 공고와 일치한다(`RegionNormalizer`).
- `matchedRequiredSkills`·`matchedPreferredSkills`는 일치한 필수·우대 기술 목록입니다.
  화면은 이 필드를 사용하며, `matchedEvidence`는 사람이 읽는 근거 문장입니다.
  `missingSkills`에는 누락된 **필수 기술만** 담습니다. (V3 마이그레이션이 기존 추천의
  일치 기술 목록을 근거 문장에서 한 번 채워 넣었습니다.)

계산은 외부 서비스나 현재 시간에 의존하지 않아 같은 입력이면 같은 점수를 냅니다.
`RecommendationScore.toEntity()`로 점수와 근거를 기존 `Recommendation` 엔티티에
저장할 수 있습니다. 6단계 API가 이 계산 결과를 사용합니다.

## 현재 API와 다음 구현

- `POST /api/profiles`, `GET /api/profiles/me`, `PUT /api/profiles/me`: 인증 사용자 프로필
- `POST /api/job-analysis/file`: PDF 텍스트 추출·OCR 후 검토용 초안(`draftId`) 반환. 점수는 계산하지 않음
- `POST /api/job-analysis/text`: 직접 입력(`TEXT`) 또는 확인한 파일 텍스트(`FILE`, `draftId`+`confirmedTextHash`) 분석
- `GET /api/job-analysis/{analysisId}`: 본인 분석 결과 조회(다른 사용자는 404), 프로필 변경 시 `stale=true`
- `POST /api/job-analysis/{analysisId}/role-analysis`: 실패한 직무 분석만 재시도(점수 불변)
- `/actuator/health`: 상태 확인

공고 목록·자동 추천·외부 공고 등록·알림 API는 제거했습니다.

### 입력 공고 분석 (`jobanalysis/`)

고정 순서 파이프라인입니다. ① ai-service `POST /internal/jobs/extract`가 Claude로 공고를 구조화하고
원문 인용이 없는 값은 버립니다. ② `JobMatchScorer`가 구조화 결과를 `RecommendationCalculator`에 넣어
기존 가중치 그대로 점수를 냅니다(LLM은 점수를 만들거나 바꾸지 않음). ③ `POST /internal/jobs/analyze-role`이
7개 항목 직무 분석을 만들고, 프로필 근거는 Spring이 보낸 항목 ID와 인용만 허용합니다.
②까지 성공하면 결과를 저장하고, ③이 실패하면 `roleStatus=FAILED`로 저장한 뒤 직무 분석만 재시도할 수 있습니다.

- 같은 원문 해시·프로필 스냅샷 해시·계산기 버전이면 저장된 결과를 돌려줍니다(LLM 재호출 없음).
- `job_analyses`에는 확인 원문, 구조화 JSON, 프로필 스냅샷, 부분 점수, 모델·프롬프트·계산기·의미 유사도 버전을 저장합니다.
  보존 기간은 `ONFIT_RECOMMENDATION_RETENTION_DAYS`(기본 30일)이고, 파일 초안은 24시간만 분석에 쓸 수 있습니다.
- LLM 연동: 백엔드 `ONFIT_LLM_ENABLED=true`, `ONFIT_LLM_URL`(기본 `http://127.0.0.1:8001`), `ONFIT_AI_TOKEN`;
  ai-service `AI_LLM_ENABLED=true`, `AI_SERVICE_TOKEN`, `ANTHROPIC_API_KEY`, 선택 `AI_LLM_MODEL`(기본 `claude-opus-5-5`).
  꺼져 있으면 분석 API는 503과 안내 문구를 반환합니다.
- 켜면 확인한 공고 원문과 프로필 요약(연락처·URL 마스킹, 프로젝트 URL 제외)이 Anthropic API로 전송됩니다.
