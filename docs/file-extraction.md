# 공고 파일 텍스트 추출 · v1

파일 모드에서 사용자가 JPG·PNG·WebP·PDF 한 개를 선택하면 브라우저가 `POST /api/job-text/extract`에 `multipart/form-data`의 `file`로 보낸다.
서버는 파일명 확장자, 선언된 MIME, 실제 파일 서명과 크기를 검사한다. PDFBox가 PDF의 각 페이지 텍스트를 추출하고,
텍스트가 20자 미만인 페이지와 이미지는 로컬 Tesseract `kor+eng` OCR로 읽는다.
[PDFBox 3 로더](https://pdfbox.apache.org/3.0/migration.html)와 [Tesseract 명령행](https://tesseract-ocr.github.io/tessdoc/Command-Line-Usage.html)을 사용한다.

응답은 `{"text":"...","method":"PDF_TEXT|PDF_TEXT_AND_OCR|OCR","pages":1,"truncated":false}`이다.
서버는 점수를 계산하거나 추출 텍스트를 프로필·추천 DB에 저장하지 않는다. 화면의 키워드 비교는 **사용자가 원본과 대조하고
텍스트 확인 버튼을 누른 뒤에만** 실행된다. 확인 후 다시 수정하면 확인 상태가 취소된다. 파일만 올리거나 추출이 실패하면
분석 버튼은 비활성이고, 원본을 보며 20자 이상을 직접 입력·확인할 수 있다. 텍스트 입력 모드는 기존대로 직접 입력한 문구를 분석한다.

## 한도와 오류

- 최대 10MiB/파일, 요청 11MB, PDF 1~10쪽, PNG/JPEG 2천만 픽셀, 추출 결과 15,000자다.
  서버가 긴 텍스트를 자르면 `truncated=true`를 표시해 사용자가 원본의 나머지를 확인하게 한다.
  WebP는 서명을 검사하고 실제 디코딩은 Tesseract가 검증한다.
- 잘못된 형식·서명은 415/422, 초과 크기는 413, 손상·암호화 PDF나 텍스트 없는 결과는 422다.
  OCR 미설치·시간 초과는 503이다. 응답은 `ProblemDetail.detail`에 사용자용 문구를 담는다.
- OCR은 페이지별 20초 제한을 둔다. 10쪽을 순서대로 처리하므로 최악의 응답 시간이 길 수 있다.
  운영에서 처리량을 늘릴 때는 별도 작업 큐·전체 시간 제한을 추가해야 한다.
- 현재 클라이언트 취소·교체는 진행 중 요청을 중단하고 늦게 도착한 응답을 무시한다.
  이미 시작된 서버 작업은 최대 페이지 처리 시간까지 계속될 수 있다.

## 설치와 파일 보관

서버에 Tesseract 실행 파일과 `kor`, `eng` 언어 데이터를 설치하고 `tesseract --list-langs`로 확인한다.
`ONFIT_OCR_COMMAND`로 실행 파일 경로를 바꾸거나 `ONFIT_OCR_ENABLED=false`로 OCR을 끌 수 있다.
꺼져 있거나 설치되지 않은 환경에서도 일반 텍스트 PDF 추출과 직접 입력은 동작한다.
CI의 Linux 백엔드 작업은 `tesseract-ocr`, `tesseract-ocr-kor`를 설치하고 실제 OCR 테스트를 실행한다.

업로드는 메모리/Servlet 임시 multipart 영역에서 요청 동안만 처리하며 애플리케이션 DB·영구 파일 저장소에 보관하지 않는다.
OCR용 임시 이미지·출력 파일은 작업 종료 시 삭제한다. 브라우저의 미리보기 URL·수정 텍스트는 탭을 떠나거나 새로고침하면
사라지며 localStorage에 저장하지 않는다. 취소·실패 시에도 영구 보관하지 않는다.
파일명·본문·추출 텍스트는 요청 로그에 기록하지 않는다. 비정상 종료로 남은 OS 임시 파일은 운영 환경의 임시 디렉터리
정리 정책에 따라 제거한다.

검증: 백엔드 `test`의 실제 PDF 텍스트 추출·이미지/스캔 PDF 경로·형식/크기 오류 테스트,
OCR 설치 환경의 실제 엔진 테스트, 프론트 `npm test`와 `node tests/browser-smoke.mjs`를 사용한다.
