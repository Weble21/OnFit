# 공고 수집 및 이메일 알림 실행

GitHub Actions는 매일 한국 시간 **11:00 / 17:00**(`0 2,8 * * *`, UTC)에 실행한다. 실행 시각 지연은 가능하다. 기본적으로 workflow와 이메일 발송은 꺼져 있다. Python 3.12의 표준 라이브러리만 사용한다.

## 구현 범위

- 사람인: 공식 `oapi.saramin.co.kr/job-search` XML API, 기업명 검색·정확한 기업명 확인·페이지 제한·마감 상태 매핑. 응답의 출처 표기 파라미터를 보존한다. API에서 제공하지 않는 공고 본문과 필수 기술을 추측하지 않는다. 최근 수정순(`sort=ud`)으로 읽고, 조회 기간(`ONFIT_COLLECTOR_LOOKBACK_HOURS`, 기본 72시간)보다 오래 전에 수정된 공고가 나오면 그 기업의 검색을 멈춘다. 수정 시각이 없거나 숫자 형식이 아니면 게시 시각으로 대신 중단하지 않고 페이지·요청 한도 안에서 계속 읽는다. 최초 실행과 처음 추가한 기업도 이 조회 범위를 사용하므로 과거 공고 전체를 가져오는 기능은 아니다. 72시간 이상 중단하거나 한도를 초과하면 공고가 누락될 수 있다.
- 기업명: `(주)`, `㈜`, `주식회사`, `(유)` 같은 법인 표기를 빼고 정확히 비교한다. 저장하는 기업명과 백엔드의 관심 기업 이름에서도 같은 표기를 뺀다. 이름의 일부만 같은 기업(예: `카카오뱅크`)은 제외한다.
- 원티드: 공식 v1 기업 검색 → 기업별 진행 공고 → 공고 상세 API. v2는 전체 탐색 API만 제공하므로 기업별 수집과 상세는 v1을 사용한다. 공고 본문에서 자격요건·우대사항을 구분해 설명에 보존한다. `skill_tags`는 필수·우대 구분 근거가 없어 필수 기술로 승격하지 않는다.
- 잡코리아: **직접 크롤러를 구현하지 않았다.** 공고 수집용 공개 API·허용 경로를 확정하지 못했으므로, 승인된 제휴 연동을 위한 JSON 피드 어댑터를 구현했다. 이 설정이 없으면 출처별 실패로 표시하며 다른 두 출처의 수집과 알림은 계속한다. 세 출처가 모두 연결된 것으로 표시하지 않는다.
- LLM Agent의 본문 구조화는 이번 작업에 포함하지 않는다. 수집과 기존 추천 점수 계산·알림을 연결한 버전이다.

## 1. 백엔드

운영 HTTPS 백엔드와 PostgreSQL이 필요하다. GitHub-hosted runner는 개발 PC의 localhost에 접속할 수 없다.

루트 `.env`는 `.env.example`을 참고한다. 로컬 `./gradlew bootRun`은 백엔드 설정을 읽지만 Python은 `.env`를 자동으로 읽지 않는다. Python을 직접 실행할 때에는 환경변수를 주입한다. 배포에서는 비밀 저장소/configtree를 사용한다.

```dotenv
ONFIT_INGESTION_ENABLED=true
ONFIT_ALERTS_ENABLED=true
ONFIT_APP_URL=https://your-onfit.example
ONFIT_MAIL_FROM=your-verified-sender@example.com
ONFIT_SMTP_HOST=your-smtp-host
ONFIT_SMTP_PORT=587
ONFIT_SMTP_USERNAME=your-smtp-user
ONFIT_SMTP_PASSWORD=<secret>
ONFIT_SARAMIN_PERMISSION_EVIDENCE=<승인 내역과 저장·추천·이메일 활용 범위>
ONFIT_WANTED_PERMISSION_EVIDENCE=<승인 내역과 활용 범위>
ONFIT_JOBKOREA_PERMISSION_EVIDENCE=<제휴 승인 내역과 활용 범위>
ONFIT_JOBKOREA_TERMS_URL=https://<승인된 계약 또는 약관 위치>
```

SMTP는 인증과 필수 STARTTLS, 연결·읽기·쓰기 타임아웃을 사용한다. 실제 발송 제공자의 발신 주소 인증과 전송 한도를 확인한다. 키가 없는 상태에서 `true`만 설정해도 출처 승인 검증이 통과하지 않는다.

백엔드 시작 시 Flyway **V10**이 구독·발송 이력·배치 잠금 테이블을, **V11**이 수신 주소별 일일 인증 메일 제한 테이블을 추가한다. 기존 migration 파일을 수정하지 않는다.

## 2. Keycloak 서비스 계정

사용자 로그인용 `onfit-web`과 별도로 confidential client `onfit-collector`를 만든다. Client authentication과 Service accounts를 켜고 Standard flow/Direct access grants는 끈다.

- API 토큰의 `aud`에 기존 백엔드가 요구하는 `onfit-api`를 넣는 audience mapper를 설정한다.
- client scopes `jobs:import`, `jobs:notify`를 만들고 **Include in token scope**를 켠다. 웹 로그인 클라이언트에는 이 scope를 연결하지 않는다.
- 이 두 scope를 collector client에만 연결한다. `scope=jobs:import jobs:notify`로 client credentials 토큰을 발급한다.
- Client secret은 GitHub Environment secret에 저장한다. 사용자 토큰을 복사하거나 인증을 끄는 방식으로 연결하지 않는다.

`GET /api/admin/alerts/targets`와 import에는 `jobs:import`, `POST /api/admin/alerts/run`에는 **별도 `jobs:notify`**가 필요하다.

## 3. GitHub Actions

저장소 Settings → Environments에서 `job-alerts`를 만든다. 해당 Environment에 아래 값을 설정한다. URL은 모두 HTTPS다. 비밀값을 채팅이나 커밋에 붙여 넣지 않는다.

| 구분 | 이름 |
| --- | --- |
| Variables | `ONFIT_BACKEND_URL`, `ONFIT_OIDC_ISSUER`, `ONFIT_COLLECTOR_CLIENT_ID` |
| Variables | `ONFIT_SARAMIN_PERMISSION_EVIDENCE`, `ONFIT_WANTED_PERMISSION_EVIDENCE`, `ONFIT_JOBKOREA_PERMISSION_EVIDENCE` |
| Variables | `ONFIT_JOBKOREA_FEED_URL` |
| Secrets | `ONFIT_COLLECTOR_CLIENT_SECRET`, `ONFIT_SARAMIN_API_KEY` |
| Secrets | `ONFIT_WANTED_CLIENT_ID`, `ONFIT_WANTED_CLIENT_SECRET`, `ONFIT_JOBKOREA_FEED_TOKEN` |

설정 및 테스트용 수신자 점검이 끝나면 **repository variable** `ONFIT_JOB_ALERTS_ENABLED=true`로 활성화한다. 이 값은 job 시작 전 평가되므로 Environment variable로만 두면 활성화되지 않는다. workflow는 기본 브랜치에 있어야 한다. 수동 실행도 같은 활성화 조건을 따른다.

workflow에는 동시 실행 방지와 25분 시간 상한이 있다. API 실패는 URL·인증키·본문 대신 출처, 오류 종류, HTTP 상태만 로그에 남긴다. 일부 출처 실패에서도 알림을 시도하지만 최종 workflow 결과는 실패로 남겨 운영자가 확인하게 한다.

## 4. 사용자 설정

로그인 → 프로필 저장 → **공고 이메일 알림** 메뉴에서 수신 주소 입력 → 6자리 인증번호 확인 → 관심 기업과 최소 추천 점수 설정 → 수신 동의 후 저장.

- 인증번호는 15분 유효, 사용자별 1분 재발급 제한, 최대 5회 확인이다. 별도로 **수신 주소당 하루 인증 메일 발송 시도 최대 5회**를 적용한다. 다른 계정도 횟수를 공유하며 한국 시간 자정에 새 한도가 시작된다. 주소 앞뒤 공백·대소문자는 동일하게 취급한다. SMTP 오류·불명확한 전송도 횟수를 차감해 재요청으로 제한을 우회하지 못하게 한다. DB에는 인증번호 해시와 수신 주소 해시별 일일 횟수를 저장한다.
- OAuth 로그인용 내부 `oidc.…@onfit.invalid`를 발송 주소로 사용하지 않는다.
- 관심 기업에 **카카오**를 입력하면 **카카오·카카오페이·카카오뱅크·카카오모빌리티**로 수집·추천·알림 대상을 확장한다. `카카오페이`처럼 특정 기업을 입력하면 해당 기업만 대상으로 한다. 목록에 없는 기업은 기존처럼 정확히 비교한다. 법인 표기와 공백·대소문자를 정규화하지만 이름 일부가 겹친다는 이유로 관련 없는 기업을 포함하지 않는다.
- 기업군은 `backend/src/main/resources/company-groups.json`의 검토된 목록을 Python·Spring Boot·프론트가 함께 사용한다. 카카오 목록은 [공식 그룹 소개](https://www.kakaocorp.com/page/about/subsidiaryCompany)를 참고해 요청한 기업을 등록했다. 카카오 계열사 전체 목록이 아니며 다른 기업군도 이 파일에 검토 후 추가한다. 확장된 기업명 기준으로 중복을 제거하고 기존 출처별 요청 예산을 나눠 사용한다.
- 프로필의 관심 기업은 브라우저에 저장되어 추천 화면에서 ‘관심 기업과 연관 기업만 보기’ 필터에 사용된다. 이메일 수신용 관심 기업은 별도 ‘공고 이메일 알림’ 설정에 저장한다. 두 화면에서 입력한 기업군의 대상 목록을 미리 확인할 수 있다. 추천 점수·가중치는 바꾸지 않는다.
- 기본 최소 점수 60은 초기 설정값이다. 공고의 필수·우대 기술이 제공되지 않으면 규칙 점수가 낮아질 수 있다. 누락된 정보를 완벽한 일치로 처리하지 않는다. 실제 공고 평가 후 기준을 조정한다.
- 구독 시작 이후 처음 수집된 적합한 실제 공고만 보낸다. 이전 공고·가상 시드·마감 공고는 제외한다.
- 동일 사용자·동일 공고는 발송 이력의 유일 제약으로 중복을 막는다. 한 메일에는 최대 10개를 넣고 남은 대기 공고는 다음 평가 때 보낸다.
- 출처가 다른 동일 채용은 현재 별개의 공고다. 출처 간 동일 공고의 통합 규칙은 아직 없다.
- 수신 동의를 해제하고 저장하면 대기 공고를 취소한다. 이미 SMTP에 전달된 메일을 회수하지는 못한다.

## 5. 잡코리아 제휴 피드 계약

실제 제휴 API의 명세를 확보하면 어댑터를 그 규격으로 교체한다. 현재 bridge 계약은 다음과 같다. 이 경로가 존재한다고 가정하지 않으며 직접 운영하거나 제휴사가 제공한 주소만 설정한다.

`GET <ONFIT_JOBKOREA_FEED_URL>?company=<기업명>&page=0&size=100`, Bearer `ONFIT_JOBKOREA_FEED_TOKEN`.

```json
{"items":[{"sourceName":"jobkorea","externalId":"외부 공고 ID","sourceUrl":"https://www.jobkorea.co.kr/…","observedAt":"2026-10-12T02:00:00Z","content":{"companyName":"회사명","title":"공고 제목","roleName":"직무","responsibilities":"원문 업무 내용","location":"지역","deadline":null,"status":"OPEN","requiredSkills":[],"preferredSkills":[]}}],"hasNext":false}
```

item은 기존 `JobImportRequest` 계약이다. 최신 관측 시각은 runner가 기록한다. 승인된 원문 호스트와 데이터 유효성은 백엔드에서도 검증한다.

## 6. 운영 한도·장애 처리

- 사용자를 합쳐 동일 기업명은 한 번 수집한다. 한 실행에서 기업군 확장 후 서로 다른 기업명 최대 50개, 기업별 페이지 최대 3개, 백엔드·토큰 HTTP 요청 최대 3000회다. 짧은 수명 토큰은 실행 중 갱신한다.
- 외부 요청 한도는 **출처별로 따로** 둔다. 한 출처가 한도를 다 써도 다른 출처는 영향을 받지 않는다.

  | 출처 | 기본 한도 | 변경 환경변수 |
  | --- | --- | --- |
  | 사람인 | 150회 | `ONFIT_SARAMIN_REQUEST_BUDGET` |
  | 원티드 | 300회 | `ONFIT_WANTED_REQUEST_BUDGET` |
  | 잡코리아 | 150회 | `ONFIT_JOBKOREA_REQUEST_BUDGET` |

  사람인은 하루 최대 500회다. 기본 예산에서 정기 실행 두 번과 수동 실행 한 번은 최대 450회다. 하루 누적 횟수를 강제하는 기능은 없으므로 추가 재실행·예산 변경·같은 키를 사용하는 다른 작업은 운영자가 합산해야 한다.
- 한 출처 안에서는 남은 한도를 남은 기업 수로 나눠 기업마다 배정한다. 앞 기업이 덜 쓴 요청은 뒤 기업이 쓴다. 공고가 많은 기업 하나가 다른 기업의 수집을 막지 않는다.
- 기업이 자기 몫의 요청이나 페이지 한도를 넘으면 그 기업만 `incomplete`로 세고 다음 기업으로 넘어간다. 형식이 맞지 않는 공고 한 건과 원티드 상세 조회 404(그 사이 삭제된 공고)는 그 공고만 `skipped`로 센다. 인증 실패, 호출 제한(429), 응답 구조 변경 같은 출처 전체의 문제는 그 출처를 `failed`로 끝낸다.
- 출처별 로그는 `ok`, `partial`(일부 기업·공고를 건너뜀), `failed` 중 하나다. `partial`과 `failed`는 성공으로 보고하지 않는다. 알림 발송은 계속 진행하고 workflow 결과만 실패로 남긴다.
- 이 한도는 무제한 서비스 보장이 아니다. 운영 규모를 키우려면 DB 수집 커서, 기업별 작업 분할, 공급자별 일일 한도 관리가 필요하다.
- 알림은 평가 기준 시각으로 대상자를 고정하고 100명씩 최대 20배치 처리한다. 각 백엔드 배치는 10분 후 새 사용자 처리를 중단한다. 계속 같은 100명만 평가하지 않는다. 남은 사용자는 다음 실행에 이어서 평가한다. workflow 시간 상한을 먼저 만날 수 있다.
- 백엔드는 30분 DB lease로 배치 중첩을 막고 각 구독을 잠근다. 발송 전 `SENDING`을 커밋한다. 프로세스 종료로 남은 `SENDING`과 SMTP 결과가 불명확한 `UNKNOWN`은 자동 재전송하지 않는다.
- SMTP에는 정확히 한 번 전달을 보장하는 기능이 없다. 운영자는 공급자 전송 기록을 확인한 후 불명확한 건의 상태를 결정한다. 자동 재전송이 필요하면 idempotency를 지원하는 이메일 API로 전환한다.
- 아래 상태 집계로 대기·불명확 전송을 모니터링한다. 수신자 정보는 일반 로그에 출력하지 않는다.

```sql
SELECT state, COUNT(*) FROM job_alert_deliveries GROUP BY state;
```

실행 중단 후의 수집 커서 재개와 무기한 공고 상태 재확인은 이번 버전에 포함되지 않는다. 매번 관심 기업의 승인된 목록을 다시 읽으며 일시적으로 목록에서 누락된 공고를 마감으로 추측하지 않는다. 알려진 마감일은 추천·알림 조회에서 제외한다.

## 검증

### 실제 공고 조회만 확인

루트 `.env`에 발급받은 출처별 API 키와 사용 승인 정보를 설정한 뒤 저장소 루트에서 실행한다. `probe.py`는 `.env`의 수집용 설정만 읽으며, 백엔드 로그인·DB 저장·이메일 발송을 실행하지 않는다. 기존 `collect.py` 전체 실행과 구분한다.

```powershell
python collector/probe.py --source saramin --company 카카오
python collector/probe.py --source wanted --company 카카오
```

`카카오`는 등록된 기업군으로 확장된다. 기본 한도는 출처당 16회 요청·기업당 1페이지이며 `--pages`(1~3), `--request-budget`(1~50)로 제한된 범위에서 조절한다. 조회 요청도 공급자 일일 사용량에 포함된다.

- `ok`: 실제 응답에서 대상 공고를 매핑했다. DB import까지 검증한 것은 아니다.
- `no_matches`: API 응답은 받았지만 조회 범위에서 매칭된 공고가 없다. 기업 공고가 아예 없다는 뜻은 아니다.
- `partial`: 페이지·요청 한도나 잘못된 공고로 일부만 확인했다.
- `not_configured`: 키 또는 승인 설정이 없어 해당 출처의 외부 요청을 하지 않았다.
- `failed`: API·인증·네트워크·응답 해석 오류. 로그에는 오류 종류와 HTTP 상태만 남긴다.

잡코리아는 실제 제휴 피드 주소·토큰·승인 정보가 있어야 `--source jobkorea`로 확인할 수 있다. API 키나 토큰은 명령 인자나 채팅에 넣지 않는다. JSON 결과의 공고 예시는 회사·제목·지역·상태·마감일만 출력한다.

```powershell
python -m unittest discover -s collector -p 'test_*.py' -v
cd backend
.\gradlew.bat test postgresTest --console=plain
```

프론트는 `npm.cmd test`, `npm.cmd run check`. CI에 Python 수집기 테스트도 추가했다. 자동 테스트는 실제 외부 키와 실수신자를 사용하지 않는다.

공식 계약: [사람인](https://oapi.saramin.co.kr/guide/job-search), [원티드 v1 OpenAPI JSON](https://openapi.wanted.jobs/v1/openapi.json), [GitHub 스케줄](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule).
