# OnFit 요약

OnFit은 사용자가 입력한 채용공고를 자신의 프로필과 비교하는 서비스입니다. React 화면, Spring Boot API, PostgreSQL, Keycloak, 비공개 AI·OCR 서비스로 구성됩니다.

## 주요 흐름

1. Keycloak 로그인 후 목표 직무·지역·경험·프로젝트를 프로필로 저장합니다.
2. 공고 텍스트를 붙여넣거나 PDF·이미지를 올립니다. 파일은 텍스트만 추출해 사용자에게 확인받습니다.
3. 서버가 공고를 구조화하고 기존 점수 계산기로 적합도를 계산합니다. 의미 점수와 직무 분석은 설정된 AI 서비스가 처리합니다.
4. 분석 결과는 사용자별로 저장하며 기본 30일 뒤 정리합니다. 파일 원본은 보관하지 않습니다.

## 로컬 실행

루트 `.env`에 `ONFIT_KC_ADMIN_PASSWORD`를 설정한 뒤 저장소 루트에서 실행합니다. 전체 로컬 스택은 `compose.yaml`과 `infra/compose.local.yaml`을 사용합니다.

```powershell
docker compose up -d --build
```

화면은 `http://localhost:5173`, 백엔드는 `http://localhost:8080`, Keycloak은 `http://localhost:8081`입니다. PostgreSQL 데이터베이스 이름과 사용자는 모두 `onfit`입니다. LLM 공고 구조화·직무 분석을 사용하려면 `.env`에 `ONFIT_LLM_ENABLED=true`와 `ANTHROPIC_API_KEY`를 설정합니다. 이때 확인된 공고 텍스트와 마스킹된 프로필 항목이 Anthropic API로 전송됩니다.

## 주요 API와 검증

- 프로필: `POST /api/profiles`, `GET/PUT /api/profiles/me`
- 공고: `POST /api/job-analysis/file`(추출 초안), `POST /api/job-analysis/text`(분석), `GET /api/job-analysis/{id}`, `POST /api/job-analysis/{id}/role-analysis`
- 프런트엔드: `npm.cmd test --prefix frontend`, `npm.cmd run check --prefix frontend`
- 백엔드: `cd backend` 후 `.\gradlew.bat test`, 실제 PostgreSQL 회귀 검증은 `.\gradlew.bat postgresTest`
- AI·OCR: 각 서비스의 `test_*.py`를 `pytest`로 실행합니다.

운영 배포는 `infra/compose.release.yaml`을 사용하며 OIDC 설정, 비밀값 파일, 백업 저장소가 필요합니다. `infra/backup.sh`, `infra/restore-check.sh`, `infra/monitor.sh`가 백업·복원 점검·상태 확인을 담당합니다. 기존 Flyway 마이그레이션은 운영 데이터와 연결되므로 수정하거나 삭제하지 않습니다.
