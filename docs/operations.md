# 운영 기본기와 PostgreSQL 회귀 검증

## 자동 검증

- Java 21, Docker Linux 엔진이 필요하다. 테스트 컨테이너는 `postgres:17.6-alpine`로 고정한다.
- `backend/gradlew test`: 빠른 단위·H2 API/영속성 테스트.
- `backend/gradlew postgresTest`: 실제 PostgreSQL 필수 통합 테스트. Docker가 없으면 실패하며 자동 건너뛰지 않는다.
- `backend/gradlew check`: 두 테스트 작업을 모두 실행한다.
- `frontend`에서 `npm test`, `npm run check`: 프론트 로직·API 매핑·파일 검증·문법 검사.
- `.github/workflows/ci.yml`: push/PR마다 위 테스트를 실행하고 백엔드 결과를 artifact로 보관한다. 브랜치 보호의 필수 상태 검사는 저장소 설정에서 `backend`, `frontend`를 지정한다.

PostgreSQL 테스트는 일회용 컨테이너 안에 사례별 스키마를 만든다. 실제 개발/운영 DB에 접속하지 않는다.
새 DB 및 V1~V7에서 출발한 DB마다 최신 V8까지 마이그레이션하고 Hibernate 매핑을 검증한다.
기존 프로필·공고·추천·기술 누락·근거 데이터를 보존하며 V3 기술 목록 backfill의 연속 순서,
V4 업종 보정, V5 업종/규모 분리를 확인한다. 이후 프로필 저장/조회/수정, 목록/상세,
추천 생성/상세/재사용/변경 이력, 시드 중복·DB 유일성, 재시작, 보존 기간 삭제까지 검증한다.
`prod` 프로필은 빈 PostgreSQL에 스키마만 만들고 가상 시드·데모 사용자를 넣지 않는지도 확인한다.

## 공고 목록 계약

`GET /api/jobs?page=0&size=20`

```json
{"content":[],"page":0,"size":20,"totalElements":0,"totalPages":0,"hasNext":false}
```

- page는 0부터 시작한다. 기본 size는 20, 허용 범위는 1~100이다. 음수·범위 초과·비정수는 400이다.
- `OPEN`이며 마감일이 없거나 서비스 시간대 기준 마감일이 지나지 않은 공고를 ID 오름차순으로 조회한다.
- 범위를 벗어난 페이지는 200과 빈 content를 반환한다. 모든 페이지에 전체 개수와 다음 페이지 여부가 있다.
- SQL에서 페이지 범위를 제한한다. 추천 계산은 목록 페이지 크기에 상관없이 열린 전체 공고를 사용한다.
- 기존 배열 응답에서 페이지 객체로 계약이 변경됐다. 현재 프론트는 `/api/jobs`를 호출하지 않으므로 화면 흐름에 영향이 없다.
- offset 방식이므로 조회 도중 공고가 바뀌면 페이지 경계도 바뀔 수 있다. 대규모 실제 수집 시 cursor 방식 도입을 평가한다.

## 추천 스냅샷 보존

- 기본 보존 기간: 생성 시각부터 **30일**, UTC 기준. `ONFIT_RECOMMENDATION_RETENTION_DAYS`로 변경한다(1 이상).
- 재사용해도 생성 시각을 갱신하지 않는다. 만료된 결과는 조회 시 404이며 추천 계산에서 재사용하지 않는다.
- 매일 **03:00 UTC(12:00 KST)**에 만료 행을 삭제한다. 30일 경계보다 오래된 행만 삭제한다.
- 근거·누락 기술·일치 기술은 외래 키 `ON DELETE CASCADE`로 함께 삭제한다. 사용자와 공고는 삭제하지 않는다.
- 배치 지연이 있어도 API 조회·재사용은 기간을 지킨다. 배치 실패 로그와 삭제 건수를 모니터링한다.
- `ONFIT_RECOMMENDATION_CLEANUP_ENABLED=false`는 테스트·장애 조사용이다. 운영에서는 기본 true를 유지한다.
- `ONFIT_FIXED_DATE`는 가상 공고의 마감 판정만 고정하며 추천 보존 기간에는 영향을 주지 않는다.
- 추천 점수와 근거는 스냅샷이다. 응답의 `job`은 현재 공고 상세를 보여준다. 공고 원문 전체의 과거 버전 보존은 실제 수집 단계에서 별도로 설계한다.

## 오류와 로그

- API 오류는 RFC 9457 ProblemDetail, 사용자용 한국어 detail, requestId를 반환한다.
- 서버가 생성한 UUID를 `X-Request-ID` 헤더와 로그에 넣는다. 클라이언트가 준 ID는 로그에 사용하지 않는다.
- 예상하지 못한 오류는 500과 일반 안내만 반환한다. 예외 메시지·SQL·스택을 응답에 넣지 않는다.
- 요청 로그: HTTP 메서드, 라우트 템플릿, 상태, 처리 시간, requestId. 프로필 본문, 업로드 원문,
  이메일, 비밀번호, 토큰, 쿼리 문자열은 기록하지 않는다. SQL/bind 값 DEBUG 로깅은 운영에서 켜지 않는다.
- `prod` 파일 로그: `logs/onfit.log`(환경변수 `ONFIT_LOG_FILE`로 변경), 파일당 10MB,
  최대 14일/100MB. 호스트 로그 수집기는 동일한 접근 제한·보존 정책을 적용한다.
- 외부 health 응답은 상세 정보를 숨긴다. `/actuator/health/liveness`, `/actuator/health/readiness`로 상태를 확인한다.
- 배포 전 로그 수집·알림을 연결한다: 5분간 API 5xx 비율 1% 초과, readiness 3회 연속 실패,
  디스크 사용 80% 초과, 백업 26시간 미생성, 추천 정리 배치 실패를 초기 경보 기준으로 사용한다.

## 비밀값과 운영 프로필

- `SPRING_PROFILES_ACTIVE=prod`에서 DB_URL, DB_USERNAME, DB_PASSWORD는 필수다.
  로컬의 `onfit/onfit_local`은 개발 전용이다. 운영 프로필은 시드와 고정 날짜를 끈다.
- 배포 환경의 secret manager/보호된 환경변수로 주입한다. `.env`, 로그, 백업을 Git에 커밋하지 않는다.
  `.env` 파일을 Spring Boot가 자동으로 읽는다고 가정하지 않는다.
- 운영 DB는 private network에 둔다. DB URL은 배포 환경에 맞는 TLS 검증(`sslmode=verify-full`)을 적용한다.
- 마이그레이션용 DDL 계정과 실행용 최소 권한 계정을 배포 단계에서 분리한다. 현재 로컬 시작 방식은 한 계정이다.
- 비밀값 노출 시 로그/커밋을 지우는 것으로 끝내지 않고 즉시 폐기·교체한다. 운영 비밀값은 주기적 교체를 설정한다.
- `prod`에서는 OIDC 접근 토큰의 서명·issuer·audience·만료를 검증하고 사용자별 프로필·추천을 분리한다.
  `ONFIT_AUTH_ISSUER`, `ONFIT_AUTH_AUDIENCE`를 설정한다. 관리자 수집에는 `jobs:import` scope가 필요하다.
  프론트 실제 로그인과 TLS, 백업·알림 연결 전에는 공개 배포하지 않는다. 상세는 [deployment.md](deployment.md).

## 백업·복원 정책

- 초기 목표: **RPO 24시간, RTO 4시간**. 이는 운영 연결·복원 훈련을 통해 검증할 목표이며 현재 보장된 SLA가 아니다.
- 매일 배포 환경 PostgreSQL 클라이언트의 `pg_dump --format=custom`로 DB 전체를 백업한다.
  DB 버전보다 오래된 pg_dump를 사용하지 않는다. 마이그레이션 직전에도 별도 백업을 만든다.
- 암호화된 별도 저장소에 최소 7개 일간, 4개 주간 백업을 보관한다. 운영자 역할만 접근하며
  키는 백업과 분리한다. 백업 속 추천 데이터도 최대 30일 내 만료시키고 복원 후 정리 배치를 실행한다.
- Flyway 이력 테이블까지 포함한다. 컨테이너 볼륨이나 파일 존재만으로 백업 성공을 판단하지 않는다.
- 매월 격리 DB에 `pg_restore --no-owner --no-privileges --exit-on-error`로 복원하고,
  최신 Flyway validate/migrate, Hibernate validate, 핵심 API 조회·추천 재사용을 확인한다.
- 복원 절차: 트래픽 차단 → 새 격리 DB에 복원 → 현재 앱 버전의 마이그레이션/검증 → 만료 스냅샷 정리
  → API smoke test → DB 연결 전환 → 트래픽 재개. 기존 DB/볼륨을 검증 전에 삭제하지 않는다.
- 되돌리기는 과거 SQL 파일 수정이나 Flyway clean으로 처리하지 않는다. 새 forward migration을 만들거나
  백업을 새 DB에 복원한다. 적용된 V1~V5의 내용과 체크섬은 변경하지 않는다.
- 백업·격리 복원 점검·상태 검사는 `infra/backup.sh`, `infra/restore-check.sh`, `infra/monitor.sh`로 실행한다.
  실제 배포 환경에서 스케줄러·암호화 저장소·알림 채널을 연결한다. [배포 리허설](deployment.md)을 참고한다.

참고: 저장소 Spring Boot 버전에 맞춘 [Spring Boot Testcontainers 문서](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html),
[Testcontainers PostgreSQL 모듈](https://java.testcontainers.org/modules/databases/postgres/).
