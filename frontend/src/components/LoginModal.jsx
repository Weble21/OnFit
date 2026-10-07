import { Logo } from './ui.jsx';
import { Modal } from './Modal.jsx';

const providers = [
  { alias: 'google', label: 'Google', mark: 'G' },
  { alias: 'naver', label: '네이버', mark: 'N' },
  { alias: 'kakao', label: '카카오', mark: 'K' },
];

export function LoginModal({ onClose, onLogin, onRetry, configured, socialProviders = new Set(), error }) {
  return (
    <Modal label="로그인" onClose={onClose}>
      <div className="login-modal">
        <Logo />
        <span className="eyebrow">WELCOME TO YOUR NEXT CHAPTER</span>
        <h2>나에게 맞는 기회,<br />온핏에서 시작해요.</h2>
        <p>좋아하는 일에 한 걸음 더 가까이.</p>
        <div className="oauth-buttons">
          {providers.map(({ alias, label, mark }) => (
            <button key={alias} className={`oauth ${alias}`} data-action={`oauth-${alias}`}
              onClick={() => onLogin(alias)} disabled={!configured || !!error || !socialProviders.has(alias)}
              title={!socialProviders.has(alias) ? `${label} 로그인이 아직 설정되지 않았습니다.` : undefined}>
              <b aria-hidden="true">{mark}</b>{label}로 로그인
            </button>
          ))}
          <button className="oauth oauth-login" data-action="oauth-login" onClick={() => onLogin()} disabled={!configured || !!error}>계정으로 로그인</button>
        </div>
        {(!configured || error) && <p className="oauth-note" role="alert">{error || 'Keycloak 인증 설정이 필요합니다.'}</p>}
        {error && <button className="btn full" data-action="retry-auth" onClick={onRetry}>다시 연결하기</button>}
        <small className="privacy-note">로그인 후 내 계정의 프로필을 만들거나 수정할 수 있습니다.</small>
      </div>
    </Modal>
  );
}
