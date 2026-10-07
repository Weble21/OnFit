# 배포 준비와 운영 연결

백엔드의 `prod` 인증·시드 차단, 빌드 가능한 컨테이너, 백업·복원 및 상태 점검 스크립트를 준비했다.
외부 배포 계정·도메인·인증 제공자·수집 사이트는 지정되지 않았으므로 공개 배포는 실행하지 않았다.
프론트는 Keycloak JavaScript 어댑터로 OIDC Authorization Code + PKCE(S256) 로그인을 수행한다.
공개 배포 전에는 실제 인증 제공자·클라이언트·도메인을 연결하고 브라우저 로그인을 검증해야 한다.

## 운영 API 인증

로컬 기본 실행과 `auth` 프로필은 `http://localhost:8081/realms/onfit`과 `onfit-api`를 기본값으로 사용한다. `prod` 프로필에서는 `ONFIT_AUTH_ISSUER`와 `ONFIT_AUTH_AUDIENCE`를 필수로 설정한다.
`test` 프로필에서만 기존 H2 회귀 테스트를 위한 공용 사용자를 허용한다.
인증 제공자는 issuer 발견 문서와 서명 공개키(JWKS)를 제공해야 한다.
Spring Security가 접근 토큰의 서명·issuer·audience·만료를 검증한다.
`GET /api/jobs`, `GET /api/jobs/{id}`와 상세 정보를 숨긴 health만 비인증 접근을 허용한다.
프로필·추천·파일 추출은 `Authorization: Bearer <access_token>`이 필요하다.
관리자 수집 API는 `jobs:import` scope가 필요하다.

사용자 식별은 `issuer + subject`의 SHA-256으로 고정한다.
현재 DB의 email 열에는 `oidc.<hash>@onfit.invalid`라는 내부 식별자를 저장한다.
실제 연락처 이메일을 의미하지 않으며 이메일 claim 변경으로 다른 계정에 접근할 수 없다.
기존 데모 프로필은 운영 사용자의 프로필로 자동 연결하지 않는다.
프로필·추천 조회와 생성은 인증된 계정으로 제한된다. 인증 오류도 requestId를 가진 ProblemDetail이다.
쿠키·세션 인증을 사용하지 않으므로 API의 CSRF 검증은 끄고 stateless bearer 인증을 사용한다.

프론트는 Keycloak `onfit-web` 공개 클라이언트로 로그인·로그아웃하고, API 요청 직전에 액세스 토큰을 갱신한다.
토큰은 어댑터 메모리에만 두며 URL·localStorage에는 저장하지 않는다. 계정별 브라우저 설정은 issuer와 subject로 분리한다.
Keycloak 클라이언트의 리디렉션 URI와 웹 출처를 실제 배포 주소로 제한하고, 액세스 토큰의 `aud`에 백엔드 `ONFIT_AUTH_AUDIENCE`가 포함되도록 Audience 매퍼를 설정한다.
프론트 빌드 시 공개 설정 `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM`, `VITE_KEYCLOAK_CLIENT_ID`를 주입한다.
이 값은 브라우저에 노출되므로 클라이언트 시크릿을 넣지 않는다. 외부 도메인 배포에서 값이 없으면 로그인 버튼이 비활성화된다.
`localhost`/`127.0.0.1`에서는 개발용 Keycloak(`http://localhost:8081`, `onfit`, `onfit-web`)을 기본값으로 사용한다.
로컬 통합 로그인은 [frontend/README.md](../frontend/README.md)의 Keycloak Compose와 백엔드 `auth` 프로필을 사용한다.
운영 Keycloak에서도 같은 로그인 화면을 쓰려면 `infra/keycloak/themes/onfit`을 서버의 `themes/onfit`에 배치하고, realm의 로그인 테마를 `onfit`으로 지정한다.

근거: [Spring Boot OAuth2 설정](https://docs.spring.io/spring-boot/reference/security/oauth2.html),
[Spring Security JWT 검증](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html).
Keycloak 어댑터 사용법은 [공식 문서](https://www.keycloak.org/securing-apps/javascript-adapter)를 따른다.

## 로컬 배포 리허설

`infra/compose.release.yaml`은 별도 PostgreSQL 볼륨을 사용하며 DB 포트를 호스트에 열지 않는다.
백엔드 포트는 루프백에만 바인딩한다. 공개 환경은 TLS ingress, 관리형/암호화 DB,
비밀값 관리, DDL 계정 분리, 외부 로그·알림 연결을 배포 환경에 맞춰 적용한다.
이 Compose의 내부 DB 연결은 격리된 로컬 리허설용이다.

비밀 파일은 Git 밖에서 만들고 운영자만 읽도록 권한을 설정한다.
Compose가 파일을 secret으로 마운트하고 Spring은 configtree로 읽는다.
명령행·이미지에 DB 비밀번호를 넣지 않는다.

```powershell
$env:ONFIT_DB_PASSWORD_FILE = 'C:\secrets\onfit-db-password'
$env:ONFIT_BACKUP_DIR = 'D:\encrypted-onfit-backups'
$env:ONFIT_AUTH_ISSUER = 'https://your-identity-provider/issuer'
$env:ONFIT_AUTH_AUDIENCE = 'onfit-api'
$env:VITE_KEYCLOAK_URL = 'https://your-identity-provider'
$env:VITE_KEYCLOAK_REALM = 'onfit'
$env:VITE_KEYCLOAK_CLIENT_ID = 'onfit-web'
docker compose -f infra/compose.release.yaml config --quiet
docker compose -f infra/compose.release.yaml --profile preview up -d --build backend frontend
```

예제 경로·issuer는 실제 값으로 바꾼다. `prod`에서는 가상 시드와 고정 날짜가 기본적으로 꺼진다.
백엔드 Docker 빌드는 Java 21 Linux에서 H2·인증·추출·한국어 OCR 테스트 및 추천 평가 후 bootJar를 만든다.
실제 PostgreSQL 회귀는 별도 CI `postgresTest`에서 필수로 실행한다.
프론트 Docker 빌드는 `npm ci`, 테스트와 Vite 빌드를 실행한다.
`--profile preview`의 프론트 빌드에도 위 Keycloak 공개 설정이 필요하다.

## 백업·복원·모니터링

매일 운영 스케줄러에서 아래 백업을 실행한다. 마이그레이션 직전에도 실행한다.
스크립트는 custom-format archive 목록 검증 후 성공 파일·시각을 기록하고 7일 일간·28일 주간을 보관한다.
주간 사본은 UTC 일요일에 생성한다. 실패 시 미완성 파일을 정리하고 실패 종료 코드를 반환한다.
보관 디렉터리는 배포 환경의 암호화 저장소로 지정하며 별도 계정 접근·키 분리를 적용한다.
로컬 디렉터리만으로 외부 재해 복구 사본을 대신하지 않는다.

```powershell
docker compose -f infra/compose.release.yaml --profile ops run --rm backup
docker compose -f infra/compose.release.yaml --profile ops run --rm backup /ops/restore-check.sh /backups/daily/onfit-YYYYMMDDTHHMMSSZ.dump
```

복원 점검은 새 이름의 격리 DB만 만들고 `pg_restore --exit-on-error`로 복원한 후 Flyway 이력·행 수를 확인한다.
완료/실패 시 자신이 생성한 점검 DB만 삭제한다. 운영 DB를 덮어쓰지 않는다.
이는 archive 복원 검사다. 월별 전체 리허설에서는 새 DB를 남겨 현재 백엔드를 그 DB에 연결하고
Flyway migrate/validate·Hibernate validate·계정별 핵심 API·추천 재사용·만료 정리를 추가로 확인한다.
복원·마이그레이션·핵심 API의 자동 회귀는 `PostgresRegressionTests`가 별도로 검증한다.

모니터링 runner에서 `infra/monitor.sh`를 1분마다 실행하고 실패 종료를 실제 알림 채널에 연결한다.
`ONFIT_HEALTH_BASE_URL`, `ONFIT_BACKUP_DIR`을 지정한다.
readiness 3회 실패, 마지막 성공 백업 26시간 초과, 백업 디스크 80% 이상을 검사한다.
로그 수집기에서는 5분간 5xx 비율 1% 초과와 추천 정리 배치 실패도 경보로 연결한다.
알림 수신자·스케줄러·암호화 저장소 연결과 복원 소요 시간은 실제 배포 환경에서 확인해야 한다.
초기 목표 RPO 24시간/RTO 4시간을 리허설 측정으로 검증한다.
