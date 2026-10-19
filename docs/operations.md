> 2026-10-18: 현재 기능 범위와 검증 기준은 [수정 방안](input-analysis-plan.md)을 우선합니다.

# 운영 기본기와 PostgreSQL 회귀 검증

## 자동 검증

- Java 21, Docker Linux 엔진이 필요하다. 테스트 컨테이너는 `postgres:17.6-alpine`로 고정한다.
- `backend/gradlew test`: 빠른 단위·H2 API/영속성 테스트.
- `backend/gradlew postgresTest`: 실제 PostgreSQL 필수 통합 테스트. Docker가 없으면 실패하며 자동 건너뛰지 않는다.
- `backend/gradlew check`: 두 테스트 작업을 모두 실행한다.
- `frontend`에서 `npm test`, `npm run check`: 프론트 로직·API 매핑·파일 검증·문법 검사.
- `.github/workflows/ci.yml`: push/PR마다 위 테스트를 실행하고 백엔드 결과를 artifact로 보관한다. 브랜치 보호의 필수 상태 검사는 저장소 설정에서 `backend`, `frontend`를 지정한다.

PostgreSQL 테스트는 일회용 컨테이너 안에 사례별 스키마를 만든다. 실제 개발/운영 DB에 접속하지 않는다.
새 DB 및 V1~V12에서 출발한 DB마다 최신 V12까지 마이그레이션하고 Hibernate 매핑을 검증한다.
기존 프로필·공고·점수·근거와 V3 기술 목록 backfill, V12 자격증 전환을 확인한다.
프로필 저장/조회/수정, 폐기 API 미노출, 가상 공고 미적재, 백업/격리 복원을 검증한다.

## 데이터 보존과 제거 기능

공고 목록·자동 추천·수집·이메일 API는 제거했습니다. 기존 V1~V12 마이그레이션과
DB의 기존 공고/결과/알림 이력은 보존합니다. 운영 데이터 삭제와 테이블 DROP은 수행하지 않습니다.
기존 결과의 30일 만료 정리(`RecommendationRetention`, `RetentionSchedule`)는 유지합니다.
`ONFIT_RECOMMENDATION_RETENTION_DAYS`, `ONFIT_RECOMMENDATION_CLEANUP_ENABLED`로 제어합니다.
새 분석 저장 모델의 소유자·보존 계약은 [수정 방안](input-analysis-plan.md)에 정리했습니다.

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
  로컬의 `onfit/onfit_local`은 개발 전용이다. 가상 공고 자동 적재는 제거했으며 운영 프로필은 고정 날짜를 끈다.
- 배포 환경의 secret manager/보호된 환경변수로 주입한다. `.env`, 로그, 백업을 Git에 커밋하지 않는다.
  `.env` 파일을 Spring Boot가 자동으로 읽는다고 가정하지 않는다.
- 운영 DB는 private network에 둔다. DB URL은 배포 환경에 맞는 TLS 검증(`sslmode=verify-full`)을 적용한다.
- 마이그레이션용 DDL 계정과 실행용 최소 권한 계정을 배포 단계에서 분리한다. 현재 로컬 시작 방식은 한 계정이다.
- 비밀값 노출 시 로그/커밋을 지우는 것으로 끝내지 않고 즉시 폐기·교체한다. 운영 비밀값은 주기적 교체를 설정한다.
- `prod`에서는 OIDC 접근 토큰의 서명·issuer·audience·만료를 검증하고 사용자별 프로필·추천을 분리한다.
  `ONFIT_AUTH_ISSUER`, `ONFIT_AUTH_AUDIENCE`를 설정한다. 관리자 수집 경로는 차단한다.
  프론트 실제 로그인과 TLS, 백업·알림 연결 전에는 공개 배포하지 않는다. 상세는 [deployment.md](deployment.md).

## 백업·복원 정책

- 초기 목표: **RPO 24시간, RTO 4시간**. 이는 운영 연결·복원 훈련을 통해 검증할 목표이며 현재 보장된 SLA가 아니다.
- 매일 배포 환경 PostgreSQL 클라이언트의 `pg_dump --format=custom`로 DB 전체를 백업한다.
  DB 버전보다 오래된 pg_dump를 사용하지 않는다. 마이그레이션 직전에도 별도 백업을 만든다.
- 암호화된 별도 저장소에 최소 7개 일간, 4개 주간 백업을 보관한다. 운영자 역할만 접근하며
  키는 백업과 분리한다. 백업 속 추천 데이터도 최대 30일 내 만료시키고 복원 후 정리 배치를 실행한다.
- Flyway 이력 테이블까지 포함한다. 컨테이너 볼륨이나 파일 존재만으로 백업 성공을 판단하지 않는다.
- 매월 격리 DB에 `pg_restore --no-owner --no-privileges --exit-on-error`로 복원하고,
  최신 Flyway validate/migrate, Hibernate validate, 프로필 API와 기존 데이터 보존을 확인한다.
- 복원 절차: 트래픽 차단 → 새 격리 DB에 복원 → 현재 앱 버전의 마이그레이션/검증 → 만료 스냅샷 정리
  → API smoke test → DB 연결 전환 → 트래픽 재개. 기존 DB/볼륨을 검증 전에 삭제하지 않는다.
- 되돌리기는 과거 SQL 파일 수정이나 Flyway clean으로 처리하지 않는다. 새 forward migration을 만들거나
  백업을 새 DB에 복원한다. 적용된 V1~V5의 내용과 체크섬은 변경하지 않는다.
- 백업·격리 복원 점검·상태 검사는 `infra/backup.sh`, `infra/restore-check.sh`, `infra/monitor.sh`로 실행한다.
  실제 배포 환경에서 스케줄러·암호화 저장소·알림 채널을 연결한다. [배포 리허설](deployment.md)을 참고한다.

참고: 저장소 Spring Boot 버전에 맞춘 [Spring Boot Testcontainers 문서](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html),
[Testcontainers PostgreSQL 모듈](https://java.testcontainers.org/modules/databases/postgres/).
