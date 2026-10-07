import { useState } from 'react';
import { Icon, Logo } from './ui.jsx';

/** The provider's photo when there is one that loads, otherwise the first letter of the account name. */
function AvatarContent({ name, picture }) {
  const [failed, setFailed] = useState(null);
  if (picture && failed !== picture) {
    // Some photo hosts (e.g. Google) refuse requests that carry a referrer.
    return <img src={picture} alt="" referrerPolicy="no-referrer" onError={() => setFailed(picture)} />;
  }
  // Array.from keeps a character outside the BMP whole; emails start with a Latin letter, shown uppercase.
  return Array.from(name?.trim() || '나')[0].toUpperCase();
}

export const NAV_ITEMS = [
  ['/recommendations', 'grid', '맞춤 기업 추천'],
  ['/analyze', 'file', '공고 적합도 분석'],
  ['/favorites', 'bookmark', '관심 기업'],
  ['/profile', 'user', '내 프로필'],
];

export function Shell({ route, favoriteCount, userName, userPicture, onLogout, children }) {
  const title = NAV_ITEMS.find(([path]) => path === route)?.[2] || '온핏';
  return (
    <div className="app-shell">
      <header className="sidebar">
        <Logo />
        <div className="nav-caption" aria-hidden="true">MY CAREER JOURNEY</div>
        <nav className="side-nav" aria-label="주요 메뉴">
          {NAV_ITEMS.map(([path, icon, label]) => (
            <a key={path} href={'#' + path} className={route === path ? 'active' : ''} aria-current={route === path ? 'page' : undefined}>
              <Icon name={icon} /><span>{label}</span>
              {path === '/favorites' && <span className="nav-count">{favoriteCount}<span className="sr-only">개 저장됨</span></span>}
            </a>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="growth-note"><Icon name="leaf" /><strong>나다운 속도로, 한 걸음씩.</strong><p>당신의 다음 챕터를<br />온핏이 함께할게요.</p></div>
          <div className="sidebar-user">
            <span className="avatar" aria-hidden="true"><AvatarContent name={userName} picture={userPicture} /></span>
            <div className="sidebar-user-text"><strong title={userName || undefined}>{userName || '나의 커리어 공간'}</strong><small>로그인한 계정</small></div>
            <button className="icon-btn" data-action="logout" aria-label="로그아웃" onClick={onLogout}><Icon name="logout" /></button>
          </div>
        </div>
      </header>
      <div className="main-wrap">
        <div className="topbar">
          <nav aria-label="현재 위치">
            <ol className="breadcrumb"><li>내 커리어</li><li aria-current="page"><Icon name="chevron" /><span>{title}</span></li></ol>
          </nav>
          <div className="topbar-right">
            <a href="#/profile" className="avatar small-avatar" aria-label="내 프로필"><AvatarContent name={userName} picture={userPicture} /></a>
          </div>
        </div>
        <main id="main-content" className="content" tabIndex={-1}>{children}</main>
        <footer className="app-footer"><p>나의 가능성을 발견하는 곳, 온핏</p><p>현재 가상 공고를 사용합니다.</p></footer>
      </div>
    </div>
  );
}
