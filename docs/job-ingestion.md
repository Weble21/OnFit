# 실제 공고 등록과 출처 추적

2026-10-06 현재 특정 사이트의 수집 허용 범위나 API 사용 계약은 확정하지 않았다.
자동 크롤러와 외부 수집 요청은 실행하지 않는다. 승인된 수집기가 전달하는 데이터를
등록하는 API와 출처·중복·변경 이력 기반을 구현했다. 기본적으로 수집 API는 비활성화다.

## 출처 승인

사이트를 정한 뒤 이용약관/API 약관에서 자동 접근, 본문 저장, 재게시, 갱신 빈도,
원문 링크·출처 표시, 보관·삭제 범위를 확인한다. robots.txt만으로 수집 허가를 판단하지 않는다.
허용 근거·확인 날짜·약관 URL을 기록하고 명시된 빈도로 수집한다. 약관이 변경되면 재검토한다.
수집기는 원문의 외부 ID, HTTPS URL, UTC 관측 시각을 전달한다.

아래 설정은 테스트용 도메인을 사용한 형식 예시이며 실제 수집 허가가 아니다.
허용 근거가 확인된 사이트만 배포 환경의 별도 설정에 넣는다.

```yaml
onfit:
  ingestion:
    enabled: true
    sources:
      approved-source:
        allowed-host: jobs.example.test
        terms-url: https://jobs.example.test/terms
        reviewed-on: 2026-10-06
        permission-evidence: '승인 문서/공식 API 계약의 식별자와 허용한 범위'
```

허용 호스트와 정확히 일치하는 HTTPS URL만 받는다. 사용자 정보가 들어간 URL,
다른 호스트, HTTP, 443 이외 포트는 거부한다. 이 API가 원문 URL을 직접 요청하지는 않는다.
운영에서는 `jobs:import` scope가 있는 접근 토큰만 등록·이력 조회를 할 수 있다.

## 등록 계약

`POST /api/admin/jobs/import`, `Content-Type: application/json`

```json
{
  "sourceName": "approved-source",
  "externalId": "posting-123",
  "sourceUrl": "https://jobs.example.test/jobs/123",
  "observedAt": "2026-10-06T00:00:00Z",
  "content": {
    "companyName": "회사명",
    "title": "백엔드 개발자 채용",
    "roleName": "백엔드 개발자",
    "industry": "IT",
    "companySize": null,
    "careerLevel": "경력 무관",
    "description": "원문에서 확인한 설명",
    "responsibilities": "API 개발",
    "location": "서울",
    "deadline": null,
    "status": "OPEN",
    "requiredSkills": ["Java"],
    "preferredSkills": ["PostgreSQL"]
  }
}
```

- 최초 등록은 201, 재관측·갱신은 200이다. 응답은 `{job, created, changed}`다.
- `sourceName + externalId`가 DB 유일 키다. 같은 원문의 재시도는 공고나 변경 이력을 늘리지 않는다.
  동시에 신규 등록한 경우 한 요청이 409를 받을 수 있으므로 수집기는 최신 시각으로 다시 시도한다.
- 최초 수집 시각은 보존하고 마지막 관측 시각만 갱신한다. 이전 관측보다 오래된 요청은 409다.
  동일 시각에 다른 원문을 전달해도 409다. 5분을 초과한 미래 시각은 400이다.
- 원문 URL, 내용, 마감일, 상태가 바뀌면 SHA-256과 JSON 원문 스냅샷을 저장한다.
  당시 약관 URL·허용 근거·검토 날짜도 변경 이력에 남긴다. 과거 스냅샷은 덮어쓰지 않는다.
- 마감일 없는 상시 채용도 `OPEN` 목록과 추천에 들어간다. 이미 마감일이 지난 `OPEN` 등록은
  `EXPIRED`로 저장한다. 목록·추천은 서비스 날짜가 지난 공고를 즉시 제외하므로 재수집이 늦어도 노출되지 않는다.
  수집기가 폐쇄·삭제를 확인한 경우 `CLOSED`로 갱신한다. 일시적인 수집 실패는 마감으로 추정하지 않는다.
- 출처별 외부 ID가 서로 다르면 별도 공고로 보존한다. 사이트 간 동일 공고 통합은 별도 규칙과 검토가 필요하다.
- `GET /api/admin/jobs/{jobId}/revisions`는 관리자에게 수집 원문·상태 변경 이력을 반환한다.

공개 목록·상세·추천 안의 공고는 `origin`, `sourceName`, `sourceUrl`, `collectedAt`, `lastSeenAt`을 반환한다.
`SYNTHETIC`은 가상 시드, `REAL`은 승인된 출처에서 등록한 공고, `LEGACY`는 출처를 확인하지 못한 기존 공고다.
V8은 기존 시드 키가 있는 행만 가상으로 표시하므로 출처 불명 데이터를 실제로 간주하지 않는다.
프론트는 이 구분과 실제 공고의 원문 링크·수집 시각을 표시한다.

## 보관과 운영 연결

현재 원문 이력은 공고가 존재하는 동안 보존하며 공고 삭제 시 함께 삭제된다.
실제 출처가 정해지면 해당 사이트의 보관·삭제 조건에 맞춰 별도 만료 작업을 추가해야 한다.
실제 출처 승인, 수집기 연결, API 키 주입, 갱신 스케줄·장애 알림을 마친 뒤 활성화한다.
가상 테스트 도메인이나 가상 회사 데이터를 실제 운영 공고로 등록하지 않는다.
