# 온핏 프론트엔드

따뜻한 크림·테라코타·세이지 색상의 반응형 SPA입니다.
React 19와 Vite로 만들었습니다. 공고·추천 화면에는 PostgreSQL과
Spring Boot 백엔드가 필요합니다.

## 실행

저장소 루트의 [`.env.example`](../.env.example)을 `.env`로 복사하고 `ONFIT_KC_ADMIN_PASSWORD`를 채운 뒤 PostgreSQL과 로컬 Keycloak을 실행합니다. `.env`는 Git에서 제외됩니다. 이미 로컬 관리자 비밀번호를 사용했다면 같은 값을 유지하세요.

```powershell
if (-not (Test-Path .env)) { Copy-Item .env.example .env }  # 처음 한 번만
docker compose --env-file .env -f infra/compose.yaml up -d
docker compose --env-file .env -f infra/compose.auth.yaml up -d
cd backend
.\gradlew.bat bootRun
```

별도 터미널에서 프로젝트 루트로 돌아와 의존성을 설치하고(처음 한 번) 개발 서버를 실행합니다.
`localhost`/`127.0.0.1`에서는 로컬 Keycloak 주소·영역·클라이언트 ID를 자동으로 사용합니다.

```powershell
npm.cmd ci --prefix frontend
npm.cmd run dev --prefix frontend
```

`./gradlew bootRun`은 루트 `.env`에서 DB 연결값과 인증 issuer·audience만 읽습니다. Keycloak 관리자 비밀번호와 소셜 로그인 Secret은 백엔드 프로세스에 전달하지 않습니다.

브라우저에서 http://localhost:5173 접속. 코드를 저장하면 화면이 바로 갱신됩니다(Vite HMR).
기존 빌드 서버(`npm start`)로 접속했다면 `npm run build`로 새 파일을 만들고 브라우저를 강력 새로고침하세요.
외부 도메인 배포용 빌드는 `VITE_KEYCLOAK_*` 값을 반드시 주입해야 합니다.
로컬 Keycloak 로그인 화면에서 계정을 새로 만들 수 있습니다. 이름·성·이메일을 채워야
Keycloak의 프로필 보완 화면 없이 앱으로 돌아옵니다. 운영 환경의 회원가입 허용 여부는 별도로 결정합니다.
일반 실행과 로컬 `auth` 프로필은 운영과 같은 JWT 소유자 검사를 사용합니다. `test` 프로필에서만 테스트용 공용 사용자를 허용합니다.
Keycloak 컨테이너는 개발용이며 `docker compose down`으로 삭제하면 테스트 사용자가 사라집니다.

로그인 화면은 `infra/keycloak/themes/onfit` 테마로 앱과 같은 색·글꼴·로고를 씁니다. 이 테마는 Keycloak 기본 화면(`keycloak.v2`)을
상속하고 CSS(`login/resources/css/onfit.css`)만 덮어씁니다. 개발 모드는 테마 캐시가 꺼져 있어 CSS를 고친 뒤 새로고침하면 바로 반영됩니다.
realm 설정(`loginTheme`, 한국어)은 처음 가져올 때만 적용되므로, 이전에 만든 컨테이너는
관리자 콘솔 **Realm settings → Themes / Localization**에서 직접 바꾸거나 컨테이너를 새로 만드세요.
브라우저 클라이언트는 Authorization Code + PKCE(S256)를 사용합니다. 토큰은 메모리에만 두고 API 호출 직전에 갱신합니다.
인증 콜백은 앱의 `/` 주소를 사용하고, 로그인 전 화면의 해시 경로는 브라우저 세션에서 복원합니다.
다른 포트는 PowerShell에서 `$env:PORT = '5174'` 설정 후 실행하세요.

### Google·네이버·카카오 로그인 연결

각 개발자 콘솔에서 웹/OIDC 앱을 만들고, 다음 **Keycloak 브로커 콜백**을 허용된 리다이렉트 URI로 등록합니다. 프론트 주소(`:5173`)는 공급자 콘솔의 콜백이 아닙니다.

| 제공자 | 개발자 콘솔 설정 | 리다이렉트 URI |
| --- | --- | --- |
| Google | OAuth 클라이언트 유형: 웹 애플리케이션 | `http://localhost:8081/realms/onfit/broker/google/endpoint` |
| 네이버 | 네이버 로그인 앱, OIDC 사용 설정 | `http://localhost:8081/realms/onfit/broker/naver/endpoint` |
| 카카오 | 카카오 로그인 및 OpenID Connect 활성화, REST API 키와 Client secret | `http://localhost:8081/realms/onfit/broker/kakao/endpoint` |

카카오 로그인에서 `KOE205`와 `Invalid scope: openid`가 나오면 해당 카카오 앱의 **카카오 로그인 → OpenID Connect → 사용 설정**을 `ON`으로 바꾸세요. 이 연동은 OIDC ID 토큰을 사용하므로 `openid` 요청을 제거하지 않습니다. Google 로그인 후 Keycloak에서 토큰 서명 오류가 나면 설정 스크립트를 다시 실행해 Google의 공개키 주소(JWKS)를 갱신하세요.

제공자 콘솔에서 `localhost` 콜백을 허용하지 않으면 공개 HTTPS Keycloak 주소를 준비하고, 위 URI의 호스트를 그 주소로 바꿉니다. 그때 `-KeycloakUrl` 인자와 백엔드 `ONFIT_AUTH_ISSUER`, 프론트 `VITE_KEYCLOAK_URL`도 같은 공개 주소를 사용해야 합니다. Google OAuth 동의 화면의 테스트 사용자/게시 상태도 확인하세요.

발급받은 값을 Git에서 제외된 루트 `.env`에 추가하고 Keycloak을 실행한 후 설정 스크립트를 실행합니다. 필요한 제공자만 설정해도 됩니다. 비밀값을 `VITE_*` 변수나 Git에 추적되는 파일에 넣지 마세요. 환경변수로 직접 전달하면 파일 값보다 우선합니다.

```dotenv
# .env: 기존 ONFIT_KC_ADMIN_PASSWORD 줄은 그대로 둡니다.
ONFIT_GOOGLE_CLIENT_ID=<Google client ID>
ONFIT_GOOGLE_CLIENT_SECRET=<Google client secret>
ONFIT_NAVER_CLIENT_ID=<Naver client ID>
ONFIT_NAVER_CLIENT_SECRET=<Naver client secret>
ONFIT_KAKAO_CLIENT_ID=<Kakao REST API key>
ONFIT_KAKAO_CLIENT_SECRET=<Kakao client secret>
```

Git Bash에서 저장소 루트 기준으로 실행합니다. Windows PowerShell의 기본 실행 정책이 스크립트를 막을 수 있어 이 명령에서만 우회합니다.

```bash
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ./infra/keycloak/configure-social.ps1
```

스크립트는 제공자를 Keycloak에 등록/갱신하고, 소셜 첫 로그인에서 **계정 정보 업데이트(Review Profile)** 단계를 생략하는 별도 흐름을 연결합니다. Keycloak의 이메일·이름·성 필수 조건도 해제합니다. 제공자가 보내는 값만 저장하고, 없어도 로그인을 진행합니다. 닉네임은 제공자가 보내면 `nickname` 사용자 속성으로 저장·갱신됩니다. Google은 별도 닉네임을 제공하지 않아 표시 이름을 대신 사용합니다. 무작위 사용자 이름은 Keycloak 내부의 고유 계정 이름이며 닉네임과 별개입니다. 기존 계정과 이메일이 겹칠 때의 계정 연결 확인 절차는 그대로 유지됩니다.

닉네임과 프로필 사진은 `nickname`·`picture` 사용자 속성으로 저장되고(스크립트가 Keycloak 사용자 프로필에 두 속성을 선언합니다), 앱 사이드바에 이름과 사진으로 표시됩니다. 로그인할 때마다 제공자 값으로 갱신되므로, 설정을 바꾼 뒤에는 기존 소셜 계정도 다시 로그인해야 반영됩니다.

- **카카오**: `openid profile_nickname profile_image` 범위로 요청합니다. 카카오 앱의 **동의항목**에서 닉네임·프로필 사진을 설정하지 않으면 `KOE205`가 납니다. 범위는 `.env`의 `ONFIT_KAKAO_SCOPE`로 바꿀 수 있습니다(이메일은 `account_email` 추가).
- **네이버**: 이름·사진이 표시되지 않습니다. 네이버 ID 토큰에는 `profile` 범위를 요청해도 별명·사진·이메일이 담기지 않고(2026-10-07 확인), 프로필 API(`/v1/nid/me`)는 값을 `response` 안에 감싸 보내 Keycloak이 사용자 ID 불일치로 거부합니다. 그래서 `openid` 범위만 요청하며, 앱에는 기본 문구가 표시됩니다. 표시하려면 네이버 전용 Keycloak 확장이 필요합니다.

제공자 콘솔에서 네이버의 **이메일·별명**, 카카오의 **닉네임** 동의항목을 설정하고 OIDC 사용자 정보로 실제 전달되는지 확인하세요. 카카오 이메일·이름은 제공되면 저장하지만 로그인 필수 조건은 아닙니다. Google은 `openid profile email` 범위로 이름·이메일을 요청하지만 이용자 설정에 따라 값이 빠질 수 있습니다. 사용자 식별에는 이메일·닉네임 대신 Keycloak 토큰의 고유 사용자 ID를 사용합니다. 이메일이 없는 계정은 이메일 기반 알림·계정 복구를 이용할 수 없으며, 서로 다른 소셜 제공자의 계정도 이메일 없이 자동으로 합쳐지지 않습니다. 사용자 정보를 임의 값으로 채우지 않습니다. 설정 후에는 `frontend/.env.local`에 활성화된 버튼 목록이 기록됩니다. Vite를 재시작하거나 `npm.cmd run build --prefix frontend`로 운영 번들을 다시 만드세요. Keycloak 관리 화면의 **Identity Providers**에서도 등록 결과를 확인할 수 있습니다. 기존 Keycloak 계정과 소셜 계정이 자동으로 같은 프로필이 되는 것은 아닙니다. 기존 데이터가 있는 계정은 Keycloak에서 계정 연결을 확인한 뒤 사용하세요.

Google의 기본 제공자와 네이버·카카오 OIDC 연동 근거: [Keycloak Identity Brokering](https://www.keycloak.org/docs/26.8.0/server_admin/), [네이버 OIDC](https://developers.naver.com/docs/login/devguide/devguide.md), [카카오 OIDC](https://developers.kakao.com/docs/en/kakaologin/rest-api).

개발 서버가 `/api/*`를 `http://127.0.0.1:8080`으로 전달합니다(`vite.config.js`).
백엔드 주소를 바꾸려면 `BACKEND_URL` 환경변수를 설정하세요. Live Server에는
이 프록시가 없어 API 화면이 동작하지 않습니다.

배포용 빌드를 확인하려면 빌드 후 운영 서버로 실행합니다. `server.mjs`가 `dist/`를
brotli/gzip으로 압축해 제공하고, 해시가 붙은 `assets/` 파일은 1년 캐시하며, `/api/*`를 같은 방식으로 전달합니다.

```powershell
npm.cmd run build --prefix frontend
npm.cmd start --prefix frontend
```

개발 모드는 React StrictMode라서 화면 진입 시 프로필 조회(GET)가 두 번 보일 수 있습니다. 운영 빌드에서는 한 번입니다.

빌드는 두 가지 로딩 최적화를 포함합니다.

- **랜딩 미리 렌더링:** `npm run build`가 `src/prerender.jsx`로 랜딩 화면 HTML을 만들어 `dist/index.html`에 넣습니다(`scripts/prerender.mjs`). JS가 도착하기 전에 첫 화면이 보이고, React가 실행되면 같은 화면으로 교체합니다. `#/profile`처럼 다른 화면 주소로 들어오면 `index.html`의 작은 인라인 스크립트가 미리 렌더링된 랜딩을 숨깁니다.
- **화면별 코드 분할:** 추천·프로필·분석 화면은 `React.lazy`로 별도 파일이 되고, 로그인 후 백그라운드에서 미리 받습니다. 화면 전환은 `startTransition`으로 처리해 새 화면이 준비될 때까지 이전 화면을 유지합니다.

Lighthouse 성능 점수는 개발 서버가 아니라 운영 빌드로, 시크릿 창에서 측정하세요.

## 화면

- `#/`: 서비스 소개, 로그인 팝업
- `#/profile`: 관심 업종·기업명, 목표 직무·부서, 신입/경력 및 기간, 여러 프로젝트·기술 입력
- `#/recommendations`: 기업 추천, 업종 필터, 기업·기술 검색, 정렬, 상세
- `#/analyze`: JD 텍스트 입력, 기술 일치율, 필수/우대 구분
- `#/favorites`: 관심 기업 저장 및 해제

로그인 후 서버에 프로필이 없으면 프로필 작성 화면으로, 있으면 추천 화면으로 이동합니다.

## 구현 범위

- React 컴포넌트, 기존 CSS(`src/styles.css`), 해시 라우팅(`#/profile` 등, 라우터 라이브러리 없음)을 사용합니다.
- 로그인은 Keycloak OIDC를 사용합니다. Google·네이버·카카오는 Keycloak의 외부 OIDC 제공자로 연결됩니다. 아래 설정을 완료한 제공자만 로그인 버튼이 활성화됩니다.
- 기업·공고는 모두 가상 데이터입니다.
- 공고의 업종(`industry`)과 기업 규모(`companySize`)는 별도 값입니다. 규모를 알 수 없으면 화면에 표시하지 않습니다.
- 직무·희망 지역·경력 사항·기술·프로젝트는 인증된 계정의 백엔드 프로필 API에 저장됩니다. 직무·지역·업종 선택지는 시드 공고 값을 모두 포함해야 하며 `tests/options.test.mjs`가 이를 검사합니다. 서버에 없는 관심 업종·기업명·부서와 관심 기업 목록만 계정별 localStorage에 남습니다.
- 추천 목록은 백엔드가 열린 가상 공고 36개를 계산한 결과입니다. 필수 35%, 우대 20%, 의미 20%, 경험 15%, 희망조건 10% 가중치를 쓰며 의미 점수는 현재 0점입니다.
- 관심 업종·기업명·부서는 브라우저에만 저장되며 백엔드 점수에는 반영되지 않습니다. 관심 업종은 추천 화면의 업종 필터에서 앞쪽에 표시됩니다.
- JD 분석은 등록된 기술 목록과 공고에서 인식한 기술의 키워드 일치율입니다.
  필수/우대 제목이 없으면 전체 일치율에만 반영합니다. 경력·직무 적합도나 합격 확률이 아닙니다.
- Google Fonts를 사용할 수 없으면 시스템 한글 글꼴로 표시됩니다.

## 검증

```powershell
npm.cmd run check --prefix frontend   # 빌드 확인
npm.cmd test --prefix frontend        # api·data·upload 단위 테스트
```

## 파일 구조

- `src/main.jsx`: React 진입점
- `src/App.jsx`: 화면 전환, 인증된 계정의 프로필·추천·관심 기업 상태, 모달 관리
- `src/pages/`: 화면 4개(`Landing`, `Recommendations`, `ProfilePage`, `AnalyzePage`)
- `src/components/`: 공통 UI(`ui.jsx`의 아이콘·알약·빈 화면 등), `Shell`(사이드바·상단바), `Modal`, 로그인·공고 상세 모달, `Toast`
- `src/hooks/`: `useHashRoute`(해시 라우팅), `useJobAnalysis`(공고 분석·첨부 파일 상태)
- `src/api.js`: 프로필·공고·추천 API 연결 및 화면 데이터 변환 (화면과 무관하게 단위 테스트)
- `src/data.js`: 프로필 직무·지역 선택지와 자유 텍스트 JD 분석 데모 (아직 서버 AI 분석 아님)
- `src/storage.js`: localStorage에 남기는 브라우저 전용 설정

## 백엔드 연결 위치

- `src/auth.js`: Keycloak 초기화, PKCE 로그인·로그아웃, 액세스 토큰 갱신
- `src/components/LoginModal.jsx`: Keycloak 로그인 진입
- OAuth 완료 후 프로필 유무에 따라 프로필 설정 또는 추천 화면으로 이동

배포 시에는 `npm run build`로 만든 `dist/`를 정적 호스팅이나 Spring Boot 정적 리소스로 제공하세요.

브라우저 검증은 개발 서버(또는 빌드 후 운영 서버) 실행 후 다음 명령으로 확인할 수 있습니다. Keycloak 테스트 계정을 환경변수로 주면 로그인 이후 전체 흐름까지 검증합니다.
React는 `input.value = ...`로 넣은 값을 사용자 입력으로 보지 않으므로, 테스트는 브라우저 기본 setter로 값을 넣습니다.
Windows 기본 경로에 설치된 Google Chrome을 사용하며 별도 테스트 브라우저 프로필로 실행합니다.

```powershell
$env:ONFIT_E2E_USERNAME = '<Keycloak 테스트 사용자명>'
$env:ONFIT_E2E_PASSWORD = '<Keycloak 테스트 사용자 비밀번호>'
node frontend/tests/browser-smoke.mjs
```

로그인 버튼과 Keycloak 가입 화면만 확인하려면 계정 변수 대신
`$env:ONFIT_EXPECT_AUTH_CONFIG = '1'`을 지정한 뒤 같은 테스트를 실행합니다.

기본 주소는 http://localhost:5173 입니다. 다른 주소의 서버를 검사하려면 `$env:ONFIT_URL = 'http://localhost:5174'`처럼 지정하세요.
계정 변수를 생략하면 공개 랜딩·OAuth 전용 로그인 화면만 검사합니다.

결과 스크린샷과 테스트 브라우저 프로필은 Git에서 제외된 `frontend/.preview/`에 생성됩니다.

## 사진·PDF 공고 첨부

공고 분석 화면에서 **사진 · PDF 첨부**를 선택합니다.

- JPG/JPEG, PNG, WebP, PDF 중 한 파일, 최대 10MB를 선택하거나 드래그할 수 있습니다.
- 확장자·MIME·파일 헤더를 검사하고 이미지는 실제 디코딩 가능 여부도 확인합니다.
- 이미지 미리보기, 브라우저 내장 PDF 뷰어, 원본 열기, 교체·삭제를 제공합니다.
- PDF 미리보기는 브라우저의 PDF 지원에 따라 달라집니다. PDF 전체 파싱·암호화 여부 검증은 아직 구현하지 않았습니다.
- 파일은 서버나 localStorage로 전송·저장하지 않고 현재 페이지 메모리에만 유지합니다.
- **현재 자동 텍스트 추출/OCR은 미연결입니다. 파일만 올려서는 점수를 생성하지 않습니다.**
- 첨부한 원본을 보며 공고 내용을 직접 입력하면 기존 키워드 비교 기능으로 분석할 수 있습니다.
- 텍스트 입력 모드와 첨부 파일 모드의 내용은 별도로 보존합니다. 파일 교체·삭제 시 해당 파일의 검토 텍스트와 결과를 초기화합니다.

### 이후 백엔드 연결 방향

1. Spring Boot에서 인증된 파일 업로드 요청을 검증합니다. 프론트의 파일 검사는 서버 검증을 대체하지 않습니다.
2. FastAPI에서 텍스트가 있는 PDF는 페이지별 텍스트를 직접 추출합니다.
3. 사진, 스캔 PDF, 텍스트 추출이 어려운 페이지는 한국어·영문 OCR로 처리합니다.
4. 추출한 텍스트를 프론트 검토 입력란에 반환하여 사용자가 오인식된 기술명 등을 수정하게 합니다.
5. 사용자가 확인한 텍스트로 공고 구조화와 적합도 분석을 요청합니다.

`src/upload.js`는 파일 형식 검사, `src/hooks/useJobAnalysis.js`의 `selectFiles()`는 첨부 상태 관리, `file.text`는 파일별 검토 텍스트를 담당합니다. OCR API가 추가되면 첨부 요청 순서 확인 및 취소 처리를 유지하면서 추출 결과를 연결하세요.
