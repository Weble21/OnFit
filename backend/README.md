# OnFit Backend

Spring Boot API server for the OnFit job recommendation project.

## Stack

- Java 21
- Spring Boot 4
- Spring Web MVC
- Spring Data JPA
- PostgreSQL
- Flyway
- Gradle

## Run

Start PostgreSQL from the repository root:

```powershell
docker compose -f infra/compose.yaml up -d
```

The Compose file uses `onfit` / `onfit_local` for local development. Override
`DB_PASSWORD` (and `DB_PORT` if needed) before starting the container. The
backend accepts `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD` environment variables.
If you change `DB_PORT`, set `DB_URL` to the matching host port as well.

Then run from the `backend` directory:

```powershell
./gradlew.bat bootRun
```

Flyway creates the schema on startup. Hibernate validates it and does not alter
tables automatically. PostgreSQL must be running for the API to start.

## 테스트용 채용공고 시드 (4단계)

`src/main/resources/seed/job-postings.json`에는 **실제 채용공고가 아닌 가상 공고 40개**가
들어 있습니다. 서버를 시작하면 누락된 공고만 PostgreSQL에 저장합니다. 각 공고의
`seedKey`가 고정되어 있어 재시작해도 중복되지 않습니다. 이미 저장된 공고의 내용은
JSON을 수정해도 자동 갱신되지 않습니다. 운영 환경에서 시드를 끄려면
`ONFIT_SEED_ENABLED=false`로 설정하세요. 현재 단계에는 채용공고 조회 API가 없으며,
5~6단계에서 추천 계산과 API를 추가할 예정입니다.

저장소 루트에서 적재 결과를 확인할 수 있습니다.

```powershell
docker compose -f infra/compose.yaml exec postgres psql -U onfit -d onfit -c 'SELECT status, count(*) FROM job_postings WHERE seed_key IS NOT NULL GROUP BY status ORDER BY status;'
```

```powershell
./gradlew.bat test
```

Tests use an in-memory H2 database in PostgreSQL compatibility mode to run the
same Flyway migration and validate the entity mappings. They do not replace a
real PostgreSQL startup check.

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

현재 인증은 구현 전이므로 모든 프로필 요청은 **로컬 데모 사용자**
`demo@onfit.local`에 연결됩니다. 실제 계정 구분이나 인증 기능이 아닙니다.

| 메서드 | 경로 | 동작 |
| --- | --- | --- |
| POST | `/api/profiles` | 프로필 생성 (201, 이미 있으면 409) |
| GET | `/api/profiles/me` | 데모 프로필 조회 (없으면 404) |
| PUT | `/api/profiles/me` | 데모 프로필 전체 교체 (없으면 404) |

백엔드를 실행한 상태에서 PowerShell로 확인합니다.

```powershell
$profileBody = @{
  targetRoles = @('백엔드 개발자')
  preferredLocations = @('서울')
  skills = @('Java', 'Spring Boot', 'PostgreSQL', 'AWS', 'Docker')
  certificates = @('정보처리기사', 'ADsP')
} | ConvertTo-Json -Depth 5

Invoke-RestMethod -Method Post -Uri 'http://localhost:8080/api/profiles' -ContentType 'application/json; charset=utf-8' -Body $profileBody
Invoke-RestMethod -Method Get -Uri 'http://localhost:8080/api/profiles/me'
```

`PUT /api/profiles/me`에도 같은 JSON 형식을 보내며, 전달한 목록이 기존 목록을
대체합니다. `projects`와 `experiences`는 선택 사항이고 생략하면 빈 목록으로
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
반올림합니다. 의미 유사도는 AI 연동 전까지 **항상 0점**이라 현재 최고점은 80점입니다.

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

## 공고·추천 API (6단계)

현재 인증이 없으므로 추천은 `demo@onfit.local` 한 명의 프로필을 사용합니다.
"마감일이 지나지 않음"은 `Asia/Seoul` 날짜 기준입니다(`ONFIT_TIME_ZONE`). 가상 공고의 마감일은
고정 날짜이므로 시연이나 테스트에서는 `ONFIT_FIXED_DATE=2026-09-29`처럼 기준 날짜를
고정할 수 있습니다. 테스트 프로필은 이 값을 고정해 두었습니다.
실제 사용자별 접근 제어가 아니며, 운영 전 인증이 필요합니다.

| 메서드 | 경로 | 결과 |
| --- | --- | --- |
| GET | `/api/jobs` | 상태가 `OPEN`이고 마감일이 지나지 않은 공고 목록 |
| GET | `/api/jobs/{jobId}` | 공고 상세 (마감 공고도 조회 가능) |
| POST | `/api/recommendations` | 데모 프로필로 열린 공고 전체를 계산해 점수순 목록 반환 |
| GET | `/api/recommendations/{recommendationId}` | 데모 사용자의 저장된 추천 상세 |

프로필이 없으면 추천 생성은 404를 반환합니다. `POST` 응답에는 공고, 전체·부분
점수, `matchedRequiredSkills`, `matchedPreferredSkills`, `matchedEvidence`, `missingSkills`가 포함됩니다. 같은 사용자·공고에서
점수와 근거가 동일한 최신 결과가 있으면 새 행을 만들지 않고 재사용합니다.
프로필 또는 공고가 바뀌어 결과가 달라지면 새 스냅샷을 저장합니다.

```powershell
Invoke-RestMethod http://localhost:8080/api/jobs
Invoke-RestMethod -Method Post http://localhost:8080/api/recommendations
```
