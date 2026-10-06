import { lazy, Suspense, useEffect, useRef, useState } from 'react';
import { createRecommendations, fromProfileResponse, getProfile, saveProfile, toDisplayJob } from './api.js';
import { sampleProfile } from './data.js';
import { persist, read, validProfile } from './storage.js';
import { navigate, useHashRoute } from './hooks/useHashRoute.js';
import { useJobAnalysis } from './hooks/useJobAnalysis.js';
import { NAV_ITEMS, Shell } from './components/Shell.jsx';
import { Toast, useToast } from './components/Toast.jsx';
import { LoginModal } from './components/LoginModal.jsx';
import { JobDetailModal } from './components/JobDetailModal.jsx';
import { Landing } from './pages/Landing.jsx';

// The landing page is all a first visit needs; the signed-in screens load as separate chunks.
const loadRecommendations = () => import('./pages/Recommendations.jsx');
const loadProfilePage = () => import('./pages/ProfilePage.jsx');
const loadAnalyzePage = () => import('./pages/AnalyzePage.jsx');
const Recommendations = lazy(() => loadRecommendations().then(m => ({ default: m.Recommendations })));
const ProfilePage = lazy(() => loadProfilePage().then(m => ({ default: m.ProfilePage })));
const AnalyzePage = lazy(() => loadAnalyzePage().then(m => ({ default: m.AnalyzePage })));

const ROUTES = ['/', ...NAV_ITEMS.map(([path]) => path)];
const IDLE = { status: 'idle', jobs: [], error: '' };
const DEFAULT_FILTERS = { filter: '전체', sort: 'score', search: '' };

function storedProfile() {
  const stored = read('profile', null);
  return validProfile(stored) ? stored : null;
}

function storedFavorites() {
  const stored = read('favorites', []);
  return Array.isArray(stored) ? stored : [];
}

export function App() {
  const route = useHashRoute(ROUTES);
  const [toast, showToast] = useToast();
  const [demoSession, setDemoSession] = useState(() => read('demoSession', false) === true);
  const [profile, setProfile] = useState(storedProfile);
  // Bumped whenever the profile is replaced, so the uncontrolled profile form starts over from it.
  const [profileVersion, setProfileVersion] = useState(0);
  const [favorites, setFavorites] = useState(storedFavorites);
  const [recs, setRecs] = useState(IDLE);
  const [filters, setFilters] = useState(DEFAULT_FILTERS);
  const [modal, setModal] = useState(null);
  const analysis = useJobAnalysis(profile, showToast);
  // A newer request (or a profile change) makes older recommendation responses stale.
  const recRequest = useRef(0);
  const recLoading = useRef(false);
  const shownRoute = useRef(route);

  const store = (key, value) => { if (!persist(key, value)) showToast('브라우저 저장이 제한되어 이번 화면에서만 유지됩니다.'); };

  function replaceProfile(next) {
    setProfile(next);
    if (next) store('profile', next);
    setProfileVersion(version => version + 1);
  }

  function resetRecommendations() {
    recRequest.current++;
    recLoading.current = false;
    setRecs(IDLE);
  }

  // Pick up the server copy once. Recommendations are computed from it on the server, so they need no reload.
  useEffect(() => {
    if (!demoSession) return undefined;
    let active = true;
    getProfile().then(
      response => { if (active) replaceProfile(fromProfileResponse(response, profile)); },
      error => {
        if (!active) return;
        if (error.status === 404) setProfile(null);
        else showToast(error.message);
      },
    );
    return () => { active = false; };
    // Only on first load; later replacements go through startDemo and handleSaveProfile.
  }, []);

  const wantsRecommendations = demoSession && !!profile && recs.status === 'idle'
    && (route === '/recommendations' || route === '/favorites');
  useEffect(() => {
    // The ref also keeps StrictMode's second effect run from sending a duplicate POST.
    if (!wantsRecommendations || recLoading.current) return;
    const request = ++recRequest.current;
    recLoading.current = true;
    setRecs({ ...IDLE, status: 'loading' });
    createRecommendations().then(
      results => { if (request === recRequest.current) setRecs({ status: 'ready', jobs: results.map(result => toDisplayJob(result.job, result)), error: '' }); },
      error => { if (request === recRequest.current) setRecs({ ...IDLE, status: 'error', error: error.message }); },
    ).finally(() => { if (request === recRequest.current) recLoading.current = false; });
  }, [wantsRecommendations]);

  useEffect(() => {
    setFilters(current => ({ ...current, filter: '전체', search: '' }));
    setModal(route !== '/' && !demoSession ? { type: 'login' } : null);
    document.title = (NAV_ITEMS.find(([path]) => path === route)?.[2] || '나다운 커리어의 시작') + ' | 온핏';
    if (shownRoute.current === route) return;
    shownRoute.current = route;
    window.scrollTo(0, 0);
    document.getElementById('main-content')?.focus({ preventScroll: true });
    // Runs on navigation only; startDemo and logout manage the modal themselves.
  }, [route]);

  useEffect(() => {
    // Once in the demo, fetch the other screens in the background so switching to them does not wait.
    if (!demoSession) return undefined;
    const timer = setTimeout(() => {
      for (const load of [loadRecommendations, loadProfilePage, loadAnalyzePage]) load().catch(() => {});
    }, 500);
    return () => clearTimeout(timer);
  }, [demoSession]);

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

  async function startDemo(withSample) {
    try {
      const existing = await getProfile().catch(error => { if (error.status === 404) return null; throw error; });
      let next = existing ? fromProfileResponse(existing, profile) : null;
      if (!existing && withSample) {
        next = structuredClone(sampleProfile);
        await saveProfile(next);
      }
      setDemoSession(true);
      store('demoSession', true);
      replaceProfile(next);
      resetRecommendations();
      setModal(null);
      navigate(next ? '/recommendations' : '/profile');
    } catch (error) {
      showToast(error.message);
    }
  }

  async function handleSaveProfile(next) {
    try {
      const saved = await saveProfile(next);
      replaceProfile(fromProfileResponse(saved, next));
      resetRecommendations();
      analysis.clearResult();
      navigate('/recommendations');
      showToast('프로필을 서버에 저장했어요. 추천을 계산합니다.');
    } catch (error) {
      showToast(error.message);
    }
  }

  function logout() {
    setDemoSession(false);
    store('demoSession', false);
    navigate('/');
    showToast('데모에서 나왔어요. 공용 데모 프로필은 서버에 남아 있습니다.');
  }

  function toggleFavorite(id) {
    if (!recs.jobs.some(job => job.id === id)) return;
    const had = favorites.includes(id);
    const next = had ? favorites.filter(saved => saved !== id) : [...favorites, id];
    setFavorites(next);
    store('favorites', next);
    showToast(had ? '관심 기업에서 해제했어요.' : '관심 기업에 저장했어요.');
  }

  function openJob(id) {
    if (profile && recs.jobs.some(job => job.id === id)) setModal({ type: 'job', id });
  }

  let page = null;
  if (route === '/profile') {
    page = <ProfilePage key={profileVersion} profile={profile} onSave={handleSaveProfile} onToast={showToast} />;
  } else if (route === '/analyze') {
    page = <AnalyzePage profile={profile} analysis={analysis} />;
  } else if (route === '/recommendations' || route === '/favorites') {
    page = <Recommendations savedOnly={route === '/favorites'} profile={profile} recs={recs} favorites={favorites}
      filters={filters} onFiltersChange={changes => setFilters(current => ({ ...current, ...changes }))}
      onRetry={resetRecommendations} onToggleFavorite={toggleFavorite} onOpenJob={openJob} />;
  }
  const openedJob = modal?.type === 'job' ? recs.jobs.find(job => job.id === modal.id) : null;

  return (
    <>
      {route === '/' || !demoSession
        ? <Landing onLogin={() => setModal({ type: 'login' })} onSampleDemo={() => startDemo(true)} />
        : <Shell route={route} favoriteCount={favorites.length} onLogout={logout}><Suspense fallback={null}>{page}</Suspense></Shell>}
      {modal?.type === 'login' && <LoginModal onClose={() => setModal(null)} onBlankDemo={() => startDemo(false)} onSampleDemo={() => startDemo(true)} />}
      {openedJob && <JobDetailModal job={openedJob} saved={favorites.includes(openedJob.id)} onToggleFavorite={toggleFavorite} onClose={() => setModal(null)} />}
      <Toast {...toast} />
    </>
  );
}
