# 공고 파일 텍스트 추출 · v1

파일 모드에서 사용자가 JPG·PNG·WebP·PDF 한 개를 선택하면 브라우저가 `POST /api/job-analysis/file`에 `multipart/form-data`의 `file`로 보낸다.
서버는 파일명 확장자, 선언된 MIME, 실제 파일 서명과 크기를 검사한다. PDFBox가 PDF의 각 페이지 텍스트를 추출하고,
텍스트가 20자 미만인 페이지와 이미지는 OCR로 읽는다. 기본 OCR은 별도 컨테이너로 띄우는 사설 **PaddleOCR 서비스**(`ocr-service/`)이고,
설정으로 로컬 Tesseract `kor+eng`를 대신 쓸 수 있다.
[PDFBox 3 로더](https://pdfbox.apache.org/3.0/migration.html), [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR),
[Tesseract 명령행](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html)을 사용한다.

응답은 `{"draftId":1,"text":"...","method":"PDF_TEXT|PDF_TEXT_AND_OCR|OCR","pages":1,"truncated":false}`이다.
서버는 점수를 계산하지 않고, 추출 텍스트만 사용자 소유 초안(`job_analysis_drafts`)으로 저장한다(분석에는 24시간까지 사용).
분석(`POST /api/job-analysis/text`, `inputType=FILE`)은 **사용자가 원본과 대조하고 텍스트 확인 버튼을 누른 뒤에만** 가능하며,
브라우저는 확인한 텍스트의 SHA-256(`confirmedTextHash`)을 보낸다. 확인 후 다시 수정하면 확인 상태가 취소되고, 해시가 다르면
서버가 409로 거부한다. 파일만 올리거나 추출이 실패하면 분석 버튼은 비활성이고, 원본을 보며 20자 이상을 직접 입력·확인할 수 있다.

## 한도와 오류

- 최대 10MiB/파일, 요청 11MB, PDF 1~10쪽, PNG/JPEG 2천만 픽셀, 추출 결과 15,000자다.
  서버가 긴 텍스트를 자르면 `truncated=true`를 표시해 사용자가 원본의 나머지를 확인하게 한다.
  WebP는 서명을 검사하고 실제 디코딩은 Tesseract가 검증한다.
- 잘못된 형식·서명은 415/422, 초과 크기는 413, 손상·암호화 PDF나 텍스트 없는 결과는 422다.
  OCR 미설치·시간 초과는 503이다. 응답은 `ProblemDetail.detail`에 사용자용 문구를 담는다.
- OCR 한 번(이미지 한 장 또는 PDF 한 쪽)의 시간 제한은 PaddleOCR 60초(`ONFIT_OCR_TIMEOUT_MS`), Tesseract 20초다.
  10쪽을 순서대로 처리하므로 최악의 응답 시간이 길 수 있다.
  운영에서 처리량을 늘릴 때는 별도 작업 큐·전체 시간 제한을 추가해야 한다.
- 현재 클라이언트 취소·교체는 진행 중 요청을 중단하고 늦게 도착한 응답을 무시한다.
  이미 시작된 서버 작업은 최대 페이지 처리 시간까지 계속될 수 있다.

## PaddleOCR 서비스

채용공고는 세로로 긴 이미지가 많아 PaddleOCR을 기본으로 쓴다. 백엔드는 이미지 한 장을 HTTP 본문으로
`POST /v1/ocr`에 보내고(`X-OnFit-OCR-Token` 헤더) `{"text","lines","tiles"}`를 받는다. 서비스는 이미지를 메모리에서만 읽고
저장·로그하지 않는다.

- 모델: 검출 `PP-OCRv5_mobile_det`, 인식 `korean_PP-OCRv5_mobile_rec`(한국어·영문·숫자). PaddleOCR의 통합 파이프라인은
  CPU에서 10배가량 느려서 두 모델을 직접 호출한다.
- 긴 이미지: 검출 모델은 4000px보다 긴 이미지를 줄여서 본문 글씨가 뭉개진다. 그래서 높이 1600px 띠로 나눠 읽고,
  띠끼리 160px 겹쳐 경계에 걸린 줄도 온전히 읽는다. 겹친 영역의 줄은 한 번만 남긴다.
- 정리: 같은 높이의 글자는 왼쪽부터 한 줄로 잇고, 큰 세로 간격은 빈 줄로 남겨 섹션(자격 요건·우대 사항 등)을 구분한다.
  신뢰도 0.75 미만 결과(장식 도형 등)는 버린다.
- 한도: 본문 12MiB, 2천만 픽셀. PNG·JPEG·WebP만 받고 선언한 형식과 실제 형식이 다르면 거절한다.
  투명 배경은 흰색으로 바꿔 읽는다.
- 측정(2026-10-12, Windows CPU): 860×4708px 공고 이미지 38줄을 약 3초에 읽었다. 결과의 오자는 `쿼리→퀴리` 정도였다.
- `paddlepaddle` 3.3.x는 CPU oneDNN 실행에서 오류가 나서 3.2.2로 고정했다(`requirements-paddle.txt`).

백엔드 설정:

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `ONFIT_OCR_ENGINE` | `paddle` | `paddle` 또는 `tesseract` |
| `ONFIT_OCR_URL` | `http://127.0.0.1:8002/v1/ocr` | OCR 서비스 주소. 사설망·로컬만 사용한다 |
| `ONFIT_OCR_TOKEN` | 없음 | 서비스의 `OCR_SERVICE_TOKEN`과 같은 강한 임의값. 비어 있으면 OCR은 503 |
| `ONFIT_OCR_TIMEOUT_MS` | `60000` | 1,000~300,000ms |
| `ONFIT_OCR_ENABLED` | `true` | `false`면 OCR 없이 텍스트 PDF와 직접 입력만 사용 |

서비스가 꺼져 있거나 토큰이 없으면 이미지·스캔 PDF는 503을 돌려준다. 일반 텍스트 PDF 추출과 직접 입력은 계속 동작한다.

로컬 실행(PowerShell, `ocr-service` 디렉터리). 모델은 처음 한 번 `.cache/paddlex`에 받아 둔다.

```powershell
python -m venv .venv
./.venv/Scripts/python.exe -m pip install -r requirements-paddle.txt -r requirements-test.txt
$env:PADDLE_PDX_CACHE_HOME = (Join-Path (Get-Location) '.cache/paddlex')
$env:PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK = 'True'
./.venv/Scripts/python.exe prepare_model.py
$env:OCR_SERVICE_TOKEN = '<개발 환경에서 생성한 임의 토큰>'
./.venv/Scripts/python.exe -m uvicorn app:app --factory --host 127.0.0.1 --port 8002 --no-access-log
```

다른 터미널에서 백엔드에 `ONFIT_OCR_TOKEN=<같은 토큰>`을 설정한다. 계약 테스트는 `python -m pytest test_app.py -q`이며
모델 없이 가짜 엔진으로 실행한다. 실제 서비스와 Java 클라이언트를 함께 확인하려면 서비스를 띄운 채
`ONFIT_OCR_TEST_URL`, `ONFIT_OCR_TEST_TOKEN`을 설정하고 `PaddleOcrClientTests`를 실행한다.

배포는 `infra/ocr.Dockerfile`로 이미지를 만든다. 빌드할 때 계약 테스트를 실행하고 모델을 이미지에 넣어,
실행 중에는 아무것도 내려받지 않는다. `infra/compose.release.yaml`의 `ocr` 서비스는 호스트 포트를 열지 않고
백엔드만 Compose 내부망으로 접근한다. 토큰은 `ONFIT_OCR_TOKEN_FILE` 비밀 파일 하나를 두 서비스에 함께 마운트한다.

## Tesseract (선택)

`ONFIT_OCR_ENGINE=tesseract`로 바꾸면 서버의 Tesseract 실행 파일과 `kor`, `eng` 언어 데이터를 쓴다.
`tesseract --list-langs`로 확인하고, `ONFIT_OCR_COMMAND`로 실행 파일 경로를 바꿀 수 있다.
CI의 Linux 백엔드 작업과 백엔드 Docker 이미지는 Tesseract를 설치하고 실제 Tesseract OCR 테스트를 실행한다.

## 파일 보관


업로드는 메모리/Servlet 임시 multipart 영역에서 요청 동안만 처리하며 애플리케이션 DB·영구 파일 저장소에 보관하지 않는다.
PaddleOCR 서비스는 이미지를 메모리에서만 다루고, Tesseract용 임시 이미지·출력 파일은 작업 종료 시 삭제한다. 브라우저의 미리보기 URL·수정 텍스트는 탭을 떠나거나 새로고침하면
사라지며 localStorage에 저장하지 않는다. 취소·실패 시에도 영구 보관하지 않는다.
파일명·본문·추출 텍스트는 요청 로그에 기록하지 않는다. 비정상 종료로 남은 OS 임시 파일은 운영 환경의 임시 디렉터리
정리 정책에 따라 제거한다.

검증: 백엔드 `test`의 실제 PDF 텍스트 추출·이미지/스캔 PDF 경로·형식/크기 오류 테스트,
OCR 설치 환경의 실제 엔진 테스트, 프론트 `npm test`와 `node tests/browser-smoke.mjs`를 사용한다.
