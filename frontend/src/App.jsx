import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { fromProfileResponse, getProfile, saveProfile } from './api.js';
import { persist, read } from './storage.js';
import { accountId, accountName, accountPicture, authConfigured, login, logout as oidcLogout, socialProviders } from './auth.js';
import { navigate, useHashRoute } from './hooks/useHashRoute.js';
import { useJobAnalysis } from './hooks/useJobAnalysis.js';
import { NAV_ITEMS, Shell } from './components/Shell.jsx';
import { Toast, useToast } from './components/Toast.jsx';
import { LoginModal } from './components/LoginModal.jsx';
import { Landing } from './pages/Landing.jsx';

// The landing page is all a first visit needs; the signed-in screens load as separate chunks.
const loadProfilePage = () => import('./pages/ProfilePage.jsx');
const loadAnalyzePage = () => import('./pages/AnalyzePage.jsx');
const ProfilePage = lazy(() => loadProfilePage().then(m => ({ default: m.ProfilePage })));
const AnalyzePage = lazy(() => loadAnalyzePage().then(m => ({ default: m.AnalyzePage })));

const ROUTES = ['/', ...NAV_ITEMS.map(([path]) => path)];

export function App({ authenticated = false, authError = '' }) {
  const route = useHashRoute(ROUTES);
  const [toast, showToast] = useToast();
  const [signedIn, setSignedIn] = useState(authenticated);
  const account = signedIn ? accountId() : null;
  const [profile, setProfile] = useState(null);
  // Bumped whenever the profile is replaced, so the uncontrolled profile form starts over from it.
  const [profileVersion, setProfileVersion] = useState(0);
  const [modal, setModal] = useState(null);
  const analysis = useJobAnalysis(profile, showToast);
  const shownRoute = useRef(route);

  const store = (key, value) => { if (!persist(key, value, account)) showToast('브라우저 저장이 제한되어 이번 화면에서만 유지됩니다.'); };

  function replaceProfile(next) {
    setProfile(next);
    if (next) store('preferences', { career: next.career });
    setProfileVersion(version => version + 1);
  }

  // Load the signed-in user profile once.
  useEffect(() => {
    if (!signedIn) return undefined;
    let active = true;
    getProfile().then(
      response => {
        if (!active) return;
        replaceProfile(fromProfileResponse(response, read('preferences', null, account)));
        if (location.hash === '' || location.hash === '#/') navigate('/analyze');
      },
      error => {
        if (!active) return;
        if (error.status === 404) { setProfile(null); navigate('/profile'); }
        else showToast(error.message);
      },
    );
    return () => { active = false; };
    // Only on first load; later replacements go through handleSaveProfile.
  }, []);

  useEffect(() => {
    setModal(route !== '/' && !signedIn ? { type: 'login' } : null);
    document.title = (NAV_ITEMS.find(([path]) => path === route)?.[2] || '나다운 커리어의 시작') + ' | 온핏';
    if (shownRoute.current === route) return;
    shownRoute.current = route;
    window.scrollTo(0, 0);
    document.getElementById('main-content')?.focus({ preventScroll: true });
    // Runs on navigation only; login and logout manage their redirects themselves.
  }, [route]);

  useEffect(() => {
    if (!signedIn) return undefined;
    const timer = setTimeout(() => {
      for (const load of [loadProfilePage, loadAnalyzePage]) load().catch(() => {});
    }, 500);
    return () => clearTimeout(timer);
  }, [signedIn]);

  useEffect(() => {
    const signedOut = () => { setSignedIn(false); setProfile(null); navigate('/'); };
    window.addEventListener('onfit-auth-logout', signedOut);
    return () => window.removeEventListener('onfit-auth-logout', signedOut);
  }, []);

  useEffect(() => {
    // Dropping a file anywhere else must not make the browser navigate away to it.
    const block = event => { if (event.dataTransfer?.types.includes('Files')) event.preventDefault(); };
    document.addEventListener('dragover', block);
    document.addEventListener('drop', block);
    return () => {
      document.removeEventListener('dragover', block);
      document.removeEventListener('drop', block);
    };
  }, []);

  async function handleLogin(provider) {
    try {
      await login(provider);
    } catch (error) {
      showToast(error.message);
    }
  }

  async function handleSaveProfile(next) {
    try {
      const saved = await saveProfile(next);
      replaceProfile(fromProfileResponse(saved, next));
      analysis.clearResult();
      navigate('/analyze');
      showToast('프로필을 서버에 저장했어요. 관심 있는 공고를 입력해 주세요.');
    } catch (error) {
      showToast(error.message);
    }
  }

  async function handleLogout() {
    try { await oidcLogout(); }
    catch { showToast('로그아웃에 실패했습니다. 다시 시도해 주세요.'); }
  }

  let page = null;
  if (route === '/profile') {
    page = <ProfilePage key={profileVersion} profile={profile} onSave={handleSaveProfile} onToast={showToast} />;
  } else if (route === '/analyze') {
    page = <AnalyzePage profile={profile} analysis={analysis} />;
  }

  return (
    <>
      {route === '/' || !signedIn
        ? <Landing signedIn={signedIn} onLogin={() => setModal({ type: 'login' })} />
        : <Shell route={route} userName={accountName()} userPicture={accountPicture()} onLogout={handleLogout}><Suspense fallback={null}>{page}</Suspense></Shell>}
      {modal?.type === 'login' && <LoginModal onClose={() => setModal(null)} onLogin={handleLogin}
        onRetry={() => location.reload()} configured={authConfigured} socialProviders={socialProviders} error={authError} />}
      <Toast {...toast} />
    </>
  );
}
