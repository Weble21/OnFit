# 의미 유사도 연동 · v1

Spring Boot 추천 API는 열린 공고를 대상으로 FastAPI에 **배치 요청 1회**를 보내고, 응답의 0~100점 의미 점수를 기존 20% 가중치에 넣는다.
FastAPI는 같은 호스트/사설망에서 다국어 문장 임베딩을 계산한다. 외부 추론 API에 프로필이나 공고를 보내지 않는다.
현재 40개 가상 공고 중 순위 대상은 36개다.

## 모델과 재현성

- 모델: [`sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`](https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/tree/f16484b452bc5449a3ad85665709a2648b51d735), 커밋 `f16484b452bc5449a3ad85665709a2648b51d735`.
- 모델 ID/커밋, 텍스트 구성 `text-v1`, 보정식 `cal-v1`을 묶어 `modelVersion`으로 송수신·스냅샷에 저장한다.
  버전이 바뀌면 같은 숫자 점수라도 이전 스냅샷을 재사용하지 않는다. V7이 기존 추천에는 `rules-only-v1`을 넣는다.
- Python 3.12와 직접·전이 의존성 버전은 `ai-service/requirements-lock.txt`에 고정했다.
  모델은 `prepare_model.py`로 먼저 로컬 캐시에 받아야 한다. 서비스 추론 때는 `local_files_only=True`이므로 모델을 다시 받지 않는다.
- 임베딩을 L2 정규화한 뒤 코사인 유사도를 계산한다. 점수는 `clamp((cosine − 0.20) / 0.60 × 100, 0, 100)`을 소수 둘째 자리 **HALF_UP**으로 반올림한다.
  이 0.20/0.80 기준은 초기 보정 가정이다. 100점은 완벽한 직무 적합성이나 채용 가능성을 뜻하지 않는다.
- 모델 커밋과 라이브러리를 고정하고 같은 CPU 환경에서 반복 요청 결과가 같은지 실제 가중치 테스트로 검증한다.
  하드웨어/런타임이 달라질 때는 소수점과 근접 동점 순위가 변할 수 있으므로 새 환경에서 전체 평가를 다시 실행한다.

## 내부 HTTP 계약

`POST /v1/semantic-score` · `Content-Type: application/json` · `X-OnFit-AI-Token: <shared secret>`.

```json
{
  "modelVersion": "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2@f16484b452bc5449a3ad85665709a2648b51d735/text-v1/cal-v1",
  "profileText": "희망 직무: 백엔드 개발자\n기술: java, postgresql\n",
  "jobs": [{"jobId": 1, "text": "공고 직무: 백엔드 개발자\n공고 제목: API 개발자\n업무: 예약 API 개발\n"}]
}
```

정상 응답은 `{"modelVersion":"<같은 버전>","scores":[{"jobId":1,"score":"57.28"}]}`이다.
요청 공고마다 점수 하나가 필요하며 `jobId`는 중복될 수 없다. 모델 버전 불일치는 409, 잘못된 형식/범위는 422, 인증 실패는 401이다.
Spring은 HTTP 200, 같은 모델 버전, 모든 요청 ID의 중복 없는 0~100점/소수 둘째 자리 이하 응답을 확인한다.
일부만 도착하거나 범위를 벗어나면 **배치 전체를 0점**으로 대체한다.

- `profileText`: 최대 2,048자. 희망 직무, 정규화한 기술 이름, 프로젝트 기술 스택, 경력 **직무명**만 기본 전달한다.
- `jobs[].text`: 공고의 직무, 제목, 업무, 설명을 최대 1,024자까지 전달한다. 공고 회사명·지역·출처 URL은 보내지 않는다.
- 회사명, 실명/표시명, 이메일, 전화번호, 이력의 회사·기간, 자격증, 프로젝트명, 프로젝트 URL, 파일/업로드 본문은 보내지 않는다.
- 프로젝트 설명은 기본 제외한다. `ONFIT_AI_INCLUDE_PROJECT_DESCRIPTIONS=true`를 설정한 경우에만 설명을 보낸다.
  현재 제품에는 프로젝트 설명 외부 처리 동의 UI가 없으므로 운영에서는 기본값을 유지한다.
  가상 프로필 평가에서는 합성 설명만 포함해 의미 품질을 측정한다.
- 텍스트에 URL/이메일/전화번호/주민번호 형태가 섞인 경우 대체한다. 일반 자유 입력에 들어간 모든 개인정보를
  자동으로 식별할 수는 없으므로 AI 서비스를 사설망에서만 운영하고 본문·토큰을 로그나 파일에 저장하지 않는다.
- Python 계약은 `profileText` 1~2,048자, 공고 1~50개, 각 공고 텍스트 1~1,024자다.
  현재 50개를 초과하는 열린 공고는 전체 0점으로 대체한다. 실제 수집으로 공고가 늘면 전체 요청 시간 제한을 갖춘 청크 처리를 추가한다.

## 장애·운영 설정

- 기본 `ONFIT_AI_ENABLED=false`에서는 규칙 점수를 즉시 사용하고 `semanticScore=0`, `modelVersion=rules-only-v1`이다.
- 켠 뒤 연결 실패, 시간 초과, 인증 실패, 잘못된 모델 버전/응답, 50개 초과 시 추천 API는 정상 응답하며
  **모든 의미 점수를 0**으로 대체하고 `modelVersion=semantic-fallback-v1`을 저장한다.
  재시도/모델 복구 시 정상 버전의 새 스냅샷을 만든다. 오류 로그에는 실패 종류만 기록하고 입력 텍스트는 기록하지 않는다.
- `ONFIT_AI_URL` 기본값은 `http://127.0.0.1:8001/v1/semantic-score`, `ONFIT_AI_TIMEOUT_MS` 기본값은 **5,000ms**(100~30,000ms 범위)다.
  HTTP 연결/응답 시간에 각각 이 한도를 적용한다. 같은 사설망/로컬 호스트만 사용하며 외부 URL을 설정하지 않는다.
- `ONFIT_AI_TOKEN`과 `AI_SERVICE_TOKEN`은 같은 강한 임의값이다. 배포 환경의 secret manager에서 주입한다.
  토큰은 요청 헤더에만 들어가고 로깅하지 않는다. 사설망 밖 연결에는 TLS 검증을 적용한다.
- FastAPI를 먼저 준비하고 모델을 캐시한 뒤 백엔드에서 `ONFIT_AI_ENABLED=true`로 켠다.
  의미 점수가 0이어도 추천 이유와 필수 기술 누락 정보는 규칙 계산 결과를 그대로 제공한다.

로컬 실행 예시(PowerShell, `ai-service` 디렉터리):

```powershell
python -m venv .venv
./.venv/Scripts/python.exe -m pip install -r requirements-test.txt
$env:HF_HOME = (Join-Path (Get-Location) '.cache/hf')
./.venv/Scripts/python.exe prepare_model.py
$env:AI_SERVICE_TOKEN = '<개발 환경에서 생성한 임의 토큰>'
./.venv/Scripts/python.exe -m uvicorn app:app --host 127.0.0.1 --port 8001 --no-access-log
```

다른 터미널에서 백엔드에 `ONFIT_AI_ENABLED=true`, `ONFIT_AI_TOKEN=<같은 토큰>`을 설정한다.
테스트는 `python -m pytest test_app.py test_model.py -q`이며 고정 모델 캐시가 있어야 `test_model.py`가 실행된다.
`python ai-service/run_evaluation.py`는 임의 포트·임시 토큰으로 로컬 FastAPI를 띄우고 Java 클라이언트의 실제 요청으로 평가한 뒤 종료한다.

## 평가 결과

[운영 기본 개인정보 설정 평가](semantic-v1-privacy-default.md)와
[합성 프로젝트 설명 포함 평가](semantic-v1-evaluation.md)는 가상 공고 40개·가상 프로필 10개를 이전 0점 기준과 비교한다.
2026-10-02 로컬 고정 모델/잠금 의존성 결과: 두 설정 모두 별칭 동등성 프로필을 중복 집계하지 않은 엄격한 기대 순서
**1건 개선, 0건 악화**. 단기 경력 프로필의 001 > 003이 점수상 분리됐다.
다만 운영 기본 설정에서 백엔드 경력 프로필의 기대 후보 Top5 포함은 5개에서 4개로 줄었다.
AI 문서 프로필의 020 > 026과 지역 선호 관련 미충족도 남아 있다. 이 결과만으로 실사용자 추천 품질을 확정할 수 없다.

CI는 API 계약·실제 고정 모델 반복 결과·Java API 장애 대체·PostgreSQL V7 업그레이드·라이브 모델 순위 게이트를 실행한다.
원격 CI 실행은 저장소 push/PR 후 확인한다.
