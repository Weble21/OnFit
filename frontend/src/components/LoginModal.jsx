import { Icon, Logo } from './ui.jsx';
import { Modal } from './Modal.jsx';

export function LoginModal({ onClose, onBlankDemo, onSampleDemo }) {
  return (
    <Modal label="로그인 및 데모 시작" onClose={onClose}>
      <div className="login-modal">
        <Logo />
        <span className="eyebrow">WELCOME TO YOUR NEXT CHAPTER</span>
        <h2>나에게 맞는 기회,<br />온핏에서 시작해요.</h2>
        <p>좋아하는 일에 한 걸음 더 가까이.</p>
        <div className="oauth-buttons">
          <button disabled className="oauth google"><b>G</b> Google로 시작하기</button>
          <button disabled className="oauth kakao"><b>●</b> 카카오로 시작하기</button>
          <button disabled className="oauth naver"><b>N</b> 네이버로 시작하기</button>
        </div>
        <p className="oauth-note">소셜 로그인은 백엔드 연동 후 제공됩니다.<br />지금은 로그인 없이 화면을 체험할 수 있어요.</p>
        <button className="btn full" data-action="blank-demo" onClick={onBlankDemo}>내 프로필로 체험하기 <Icon name="arrow" /></button>
        <button className="btn text full" data-action="sample-demo" onClick={onSampleDemo}>샘플 프로필로 둘러보기</button>
        <small className="privacy-note">프로필은 공용 데모 사용자로 서버에 저장됩니다. 실제 개인정보를 입력하지 마세요.</small>
      </div>
    </Modal>
  );
}
