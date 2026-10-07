import { Icon, Logo, Plant } from '../components/ui.jsx';

export function Landing({ onLogin }) {
  return (
    <div className="landing">
      <header className="landing-header">
        <Logo />
        <nav aria-label="주요 메뉴"><a href="#/recommendations">맞춤 기업 추천</a><a href="#/analyze">공고 적합도 분석</a></nav>
        <button className="btn small" data-action="login" onClick={onLogin}>로그인 <Icon name="arrow" /></button>
      </header>
      <main className="landing-main">
        <section className="landing-hero" aria-labelledby="landing-title">
          <div className="landing-copy">
            <span className="eyebrow"><Icon name="leaf" /> YOUR NEXT CHAPTER</span>
            <h1 id="landing-title">당신의 경험이<br />좋은 기회와<br /><em>만나는 곳.</em></h1>
            <p>어디에 지원할지 막막할 때,<br />나의 경험에서 시작하는 커리어 가이드, 온핏.</p>
            <div className="hero-actions">
              <button className="btn" data-action="login" onClick={onLogin}>나에게 맞는 기회 찾기 <Icon name="arrow" /></button>
            </div>
            <div className="landing-note"><span className="tiny-dot" aria-hidden="true"></span> 작은 프로젝트도, 첫 경험도 충분한 시작이 돼요.</div>
          </div>
          <figure className="landing-visual">
            <Plant />
            <div className="floating-card fc1">
              <span className="round-icon" aria-hidden="true"><Icon name="check" /></span>
              <div><small>나의 경험과 연결된 기회</small><strong>“이 경험, 여기서 빛날 수 있어요.”</strong></div>
            </div>
            <div className="floating-card fc2">
              <span className="mini-logo sage" aria-hidden="true">m</span>
              <div><strong>나와 잘 맞는 다음 챕터</strong><small>기술 · 경험 · 내가 원하는 방향</small></div>
              <Icon name="spark" />
            </div>
            <figcaption className="visual-caption">GROW AT YOUR OWN PACE</figcaption>
          </figure>
        </section>
        <section className="landing-features" aria-labelledby="landing-features-title">
          <h2 id="landing-features-title" className="sr-only">온핏 이용 단계</h2>
          <article><span className="feature-number" aria-hidden="true">01</span><div><h3>경험을 담고</h3><p>프로젝트와 기술, 원하는 커리어를 알려주세요.</p></div><Icon name="user" /></article>
          <article><span className="feature-number" aria-hidden="true">02</span><div><h3>가능성을 발견하고</h3><p>내 경험과 연결되는 기업을 만나보세요.</p></div><Icon name="spark" /></article>
          <article><span className="feature-number" aria-hidden="true">03</span><div><h3>자신 있게 지원해요</h3><p>공고와의 적합도, 준비할 역량을 확인하세요.</p></div><Icon name="arrow" /></article>
        </section>
      </main>
      <footer className="landing-footer"><p>© 2026 onfit. 나다운 커리어의 시작.</p><p>로그인 후 프로필과 추천을 사용할 수 있습니다.</p></footer>
    </div>
  );
}
