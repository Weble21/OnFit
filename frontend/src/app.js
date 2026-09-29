import { FILE_ACCEPT, validateAttachment, fileSize } from './upload.js';
import { sampleProfile, profileSkills, analyzeText, sampleJD, roles, regions } from './data.js';
import { getProfile, saveProfile, createRecommendations, fromProfileResponse, toDisplayJob } from './api.js';

const app = document.querySelector('#app');
const modalRoot = document.querySelector('#modal-root');
const icons = {
  grid: '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
  spark: '<path d="m12 3 2.6 6.4L21 12l-6.4 2.6L12 21l-2.6-6.4L3 12l6.4-2.6L12 3Z"/>',
  file: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8Z"/><path d="M14 2v6h6M8 13h8M8 17h5"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/>',
  bookmark: '<path d="M6 3h12v18l-6-4-6 4V3Z"/>',
  arrow: '<path d="M4 12h16m-6-6 6 6-6 6"/>',
  chevron: '<path d="m9 5 7 7-7 7"/>',
  check: '<path d="m5 12 4 4L19 6"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  close: '<path d="m6 6 12 12M18 6 6 18"/>',
  pin: '<path d="M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="2"/>',
  search: '<circle cx="10" cy="10" r="7"/><path d="m15 15 6 6"/>',
  briefcase: '<rect x="3" y="7" width="18" height="14" rx="2"/><path d="M8 7V3h8v4M3 12a24 24 0 0 0 18 0M12 12v4"/>',
  logout: '<path d="M9 4H4v16h5M9 12h12m-5-5 5 5-5 5"/>',
  leaf: '<path d="M20 3C7 1 1 10 7 16s15 0 13-13ZM5 20 15 10"/>',
};
const icon = (name, cls = '') => '<svg class="icon '+cls+'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">'+(icons[name] || icons.spark)+'</svg>';
const esc = value => String(value ?? '').replace(/[&<>"']/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));

function read(key, fallback) {
  try { return JSON.parse(localStorage.getItem('onfit.'+key)) ?? fallback; }
  catch { return fallback; }
}
function persist(key, value) {
  try { localStorage.setItem('onfit.'+key, JSON.stringify(value)); return true; }
  catch { toast('브라우저 저장이 제한되어 이번 화면에서만 유지됩니다.'); return false; }
}
function validProfile(value) {
  return value && Array.isArray(value.types) && value.types.every(v => typeof v === 'string') &&
    ['companies','role','department','career'].every(k => typeof value[k] === 'string') &&
    Array.isArray(value.projects) && value.projects.length > 0 &&
    value.projects.every(p => p && ['name','description','stack'].every(k => typeof p[k] === 'string')) &&
    (value.experiences === undefined || (Array.isArray(value.experiences) &&
      value.experiences.every(e => e && ['company','role','start','end'].every(k => typeof e[k] === 'string'))));
}
let profile = read('profile', null);
if (!validProfile(profile)) profile = null;
let jobs = [];
let recommendationsLoaded = false;
let recommendationsLoading = false;
let recommendationError = '';
let recommendationRequest = 0;
let favorites = read('favorites', []);
if (!Array.isArray(favorites)) favorites = [];
let demoSession = read('demoSession', false) === true;
let filter = '전체';
let sort = 'score';
let search = '';
let jdText = '';
let inputMode = 'text';
let attachment = null;
let attachmentText = '';
let attachmentError = '';
let attachmentBusy = false;
let attachmentRequest = 0;
let analysis = null;
let modalTrigger = null;
let toastTimer;
const emptyProfile = () => ({types: [], companies: '', role:'백엔드 개발자', location:'', department:'', career:'신입', experiences:[], projects:[{name:'', description:'', stack:''}]});
const logo = () => '<a class="brand" href="#/" aria-label="온핏 홈"><span class="brand-mark">o<span></span></span><span>온핏<span class="brand-en">onfit</span></span></a>';
const pill = (text, cls='', tag='span') => '<'+tag+' class="pill '+cls+'">'+esc(text)+'</'+tag+'>';
const pillList = (cls, items) => '<ul class="'+cls+'">'+items.map(([text, pillCls]) => pill(text, pillCls, 'li')).join('')+'</ul>';
const plant = () => '<div class="plant-art" aria-hidden="true"><div class="sun"></div><div class="orbit-line"></div><div class="stem"></div><i class="leaf l1"></i><i class="leaf l2"></i><i class="leaf l3"></i><i class="leaf l4"></i><div class="pot"></div><div class="plant-ground"></div><span class="art-star a1">✳</span><span class="art-star a2">✧</span></div>';
function toast(message) {
  const el = document.querySelector('#toast');
  el.textContent = message; el.classList.add('visible');
  clearTimeout(toastTimer); toastTimer = setTimeout(() => el.classList.remove('visible'), 3500);
}
function navigate(route) {
  if (location.hash === '#'+route) render(); else location.hash = route;
}
async function startDemo(withSample) {
  try {
    const existing = await getProfile().catch(error => { if (error.status === 404) return null; throw error; });
    if (existing) profile = fromProfileResponse(existing, profile);
    else if (withSample) {
      profile = structuredClone(sampleProfile);
      await saveProfile(profile);
    } else profile = null;
    demoSession = true; persist('demoSession', true);
    if (profile) persist('profile', profile);
    recommendationRequest++;
    recommendationsLoaded = false; recommendationsLoading = false; recommendationError = ''; jobs = [];
    closeModal();
    navigate(profile ? '/recommendations' : '/profile');
  } catch (error) { toast(error.message); }
}
function landing() {
  return '<div class="landing"><header class="landing-header">'+logo()+'<nav aria-label="주요 메뉴"><a href="#/recommendations">맞춤 기업 추천</a><a href="#/analyze">공고 적합도 분석</a></nav><button class="btn small" data-action="login">로그인 '+icon('arrow')+'</button></header>'+
    '<main class="landing-main"><section class="landing-hero" aria-labelledby="landing-title"><div class="landing-copy"><span class="eyebrow">'+icon('leaf')+' YOUR NEXT CHAPTER</span><h1 id="landing-title">당신의 경험이<br>좋은 기회와<br><em>만나는 곳.</em></h1><p>어디에 지원할지 막막할 때,<br>나의 경험에서 시작하는 커리어 가이드, 온핏.</p><div class="hero-actions"><button class="btn" data-action="login">나에게 맞는 기회 찾기 '+icon('arrow')+'</button><button class="btn text" data-action="sample-demo">먼저 둘러보기</button></div><div class="landing-note"><span class="tiny-dot" aria-hidden="true"></span> 작은 프로젝트도, 첫 경험도 충분한 시작이 돼요.</div></div>'+
    '<figure class="landing-visual">'+plant()+'<div class="floating-card fc1"><span class="round-icon" aria-hidden="true">'+icon('check')+'</span><div><small>나의 경험과 연결된 기회</small><strong>“이 경험, 여기서 빛날 수 있어요.”</strong></div></div><div class="floating-card fc2"><span class="mini-logo sage" aria-hidden="true">m</span><div><strong>나와 잘 맞는 다음 챕터</strong><small>기술 · 경험 · 내가 원하는 방향</small></div>'+icon('spark')+'</div><figcaption class="visual-caption">GROW AT YOUR OWN PACE</figcaption></figure></section>'+
    '<section class="landing-features" aria-labelledby="landing-features-title"><h2 id="landing-features-title" class="sr-only">온핏 이용 단계</h2><article><span class="feature-number" aria-hidden="true">01</span><div><h3>경험을 담고</h3><p>프로젝트와 기술, 원하는 커리어를 알려주세요.</p></div>'+icon('user')+'</article><article><span class="feature-number" aria-hidden="true">02</span><div><h3>가능성을 발견하고</h3><p>내 경험과 연결되는 기업을 만나보세요.</p></div>'+icon('spark')+'</article><article><span class="feature-number" aria-hidden="true">03</span><div><h3>자신 있게 지원해요</h3><p>공고와의 적합도, 준비할 역량을 확인하세요.</p></div>'+icon('arrow')+'</article></section></main><footer class="landing-footer"><p>© 2026 onfit. 나다운 커리어의 시작.</p><p>프론트엔드 데모 · 실제 채용 및 인증 서비스 미연결</p></footer></div>';
}
const navItems = [['/recommendations','grid','맞춤 기업 추천'],['/analyze','file','공고 적합도 분석'],['/favorites','bookmark','관심 기업'],['/profile','user','내 프로필']];
function shell(route, content) {
  const title = navItems.find(([r]) => r === route)?.[2] || '온핏';
  return '<div class="app-shell"><header class="sidebar">'+logo()+'<div class="nav-caption" aria-hidden="true">MY CAREER JOURNEY</div><nav class="side-nav" aria-label="주요 메뉴">'+navItems.map(([r,i,t])=>'<a href="#'+r+'" class="'+(route===r?'active':'')+'" '+(route===r?'aria-current="page"':'')+'>'+icon(i)+'<span>'+t+'</span>'+(r==='/favorites'?'<span class="nav-count">'+favorites.length+'<span class="sr-only">개 저장됨</span></span>':'')+'</a>').join('')+'</nav><div class="sidebar-bottom"><div class="growth-note">'+icon('leaf')+'<strong>나다운 속도로, 한 걸음씩.</strong><p>당신의 다음 챕터를<br>온핏이 함께할게요.</p></div><div class="sidebar-user"><span class="avatar" aria-hidden="true">나</span><div><strong>나의 커리어 공간</strong><small>로컬 데모 계정</small></div><button class="icon-btn" data-action="logout" aria-label="데모 나가기">'+icon('logout')+'</button></div></div></header><div class="main-wrap"><div class="topbar"><nav aria-label="현재 위치"><ol class="breadcrumb"><li>내 커리어</li><li aria-current="page">'+icon('chevron')+'<span>'+title+'</span></li></ol></nav><div class="topbar-right"><span class="demo-label"><span aria-hidden="true"></span> 미리보기 모드</span><a href="#/profile" class="avatar small-avatar" aria-label="내 프로필">나</a></div></div><main id="main-content" class="content" tabindex="-1">'+content+'</main><footer class="app-footer"><p>나의 가능성을 발견하는 곳, 온핏</p><p>가상 공고와 브라우저 내 데이터로 동작하는 데모입니다.</p></footer></div></div>';
}
function pageHeading(kicker,title,description,extra='') {
  return '<header class="page-heading"><div><span class="eyebrow">'+kicker+'</span><h1>'+title+'</h1><p>'+description+'</p></div>'+extra+'</header>';
}
async function loadRecommendations() {
  if (recommendationsLoading) return;
  const request = ++recommendationRequest;
  recommendationsLoading = true; recommendationError = '';
  try {
    // Each recommendation already carries its job, so the job list needs no separate request.
    const results = await createRecommendations();
    if (request !== recommendationRequest) return;
    jobs = results.map(result => toDisplayJob(result.job, result));
    recommendationsLoaded = true;
  } catch (error) {
    if (request === recommendationRequest) recommendationError = error.message;
  } finally {
    if (request === recommendationRequest) {
      recommendationsLoading = false;
      if (['#/recommendations', '#/favorites'].includes(location.hash)) render();
    }
  }
}
function jobCard(job) {
  const isSaved = favorites.includes(job.id);
  const matched = job.requiredMatch.matched;
  return '<article class="job-card"><div class="job-card-top"><span class="company-logo '+job.color+'" aria-hidden="true">'+esc(job.initial)+'</span><button class="icon-btn bookmark '+(isSaved?'saved':'')+'" data-action="favorite" data-id="'+job.id+'" aria-label="'+esc(job.company)+(isSaved?' 관심 기업 해제':' 관심 기업 저장')+'" aria-pressed="'+isSaved+'">'+icon('bookmark')+'</button></div><div class="company-line"><h3>'+esc(job.company)+'</h3>'+pill(job.type)+'</div><p class="job-title">'+esc(job.title)+'</p><p class="job-meta">'+icon('pin')+esc(job.location)+'<span aria-hidden="true">·</span>'+esc(job.career)+'</p>'+pillList('job-skills', job.required.map(s=>[s, matched.includes(s)?'matched':'']))+'<p class="match-reason">'+icon('spark')+'<span>'+(matched.length?esc(matched.slice(0,2).join(', '))+' 기술이 일치해요.':'필수 기술을 확인해 보세요.')+'</span></p><footer class="job-card-bottom"><div class="card-score"><strong>'+job.score+'<small>점</small></strong><span>추천 점수</span></div><button class="btn text" data-action="job" data-id="'+job.id+'" aria-label="'+esc(job.company)+' 공고 자세히 보기">자세히 보기 '+icon('arrow')+'</button></footer></article>';
}
function recommendations(savedOnly=false) {
  if (!profile) return emptyState('먼저 나의 이야기를 들려주세요','프로필을 작성하면 경험에 맞는 기업을 찾아볼 수 있어요.','/profile','프로필 작성하기',1);
  if (recommendationsLoading || !recommendationsLoaded) {
    if (recommendationError) return '<div class="empty-state" role="alert"><h1>추천을 불러오지 못했어요</h1><p>'+esc(recommendationError)+'</p><button class="btn" data-action="retry-recommendations">다시 시도</button></div>';
    return '<div class="empty-state" role="status"><h1>추천을 계산하고 있어요</h1><p>저장된 프로필과 공고를 비교하고 있습니다.</p></div>';
  }
  const query = search.toLowerCase();
  const list = jobs.filter(j=>(!savedOnly||favorites.includes(j.id)) && (filter==='전체'||j.type===filter) && (j.company+' '+j.title+' '+j.required.join(' ')).toLowerCase().includes(query));
  list.sort(sort==='name' ? (a,b)=>a.company.localeCompare(b.company,'ko') : (a,b)=>b.score-a.score || Number(a.id)-Number(b.id));
  const skills = profileSkills(profile);
  const count = jobs.length;
  return pageHeading(savedOnly?'YOUR COLLECTION':'DISCOVER YOUR POSSIBILITIES',savedOnly?'마음에 담아둔 기회':'나의 경험이, 다음 기회로.',savedOnly?'관심 있는 기업을 모아두고 차근차근 준비해 보세요.':'지금까지 쌓아온 경험을 알아봐 줄 기업들을 만나보세요.', '<a class="btn outline small" href="#/profile">'+icon('user')+' 프로필 수정</a>')+
    (!savedOnly?'<section class="welcome-banner" aria-labelledby="welcome-title"><div><span class="banner-label">YOUR NEXT CHAPTER</span><h2 id="welcome-title">나에게 맞는 곳에서,<br>더 크게 자라날 수 있도록.</h2><p>등록한 기술과 희망 직무로 '+count+'개의 가상 공고를 비교했어요.</p><a href="#/analyze" class="banner-link">눈여겨본 공고가 있나요? 직접 분석하기 '+icon('arrow')+'</a></div>'+plant()+'</section><section class="profile-strip" aria-label="나의 커리어 키워드"><div class="profile-strip-title"><span class="round-icon" aria-hidden="true">'+icon('user')+'</span><div><small>나의 커리어 키워드</small><strong>'+esc(profile.role)+' <span>· '+esc(profile.career)+'</span></strong></div></div>'+pillList('profile-tags', [...skills.slice(0,4).map(s=>[s]), ...(skills.length>4?[['+'+(skills.length-4)]]:[])])+'<a href="#/profile" class="icon-btn" aria-label="커리어 프로필 수정">'+icon('chevron')+'</a></section>':'')+
    '<section class="recommendation-section" aria-labelledby="recommendation-title"><header class="section-heading"><h2 id="recommendation-title">'+(savedOnly?'저장한 기업':'발견한 기회')+' <span>'+list.length+'<span class="sr-only">곳</span></span></h2><span class="subtle">'+icon('spark')+' 서버 계산 추천</span></header><div class="filters"><div class="filter-tabs" role="group" aria-label="기업 유형">'+['전체',...new Set(jobs.map(job=>job.type))].map(f=>'<button class="'+(filter===f?'selected':'')+'" data-action="filter" data-value="'+esc(f)+'" aria-pressed="'+(filter===f)+'">'+esc(f)+'</button>').join('')+'</div><search class="filter-controls"><label class="search-field">'+icon('search')+'<input id="company-search" aria-label="기업 또는 기술 검색" placeholder="기업·기술 검색" value="'+esc(search)+'"></label><select id="sort" aria-label="추천 정렬"><option value="score" '+(sort==='score'?'selected':'')+'>점수순</option><option value="name" '+(sort==='name'?'selected':'')+'>기업명순</option></select></search></div><div class="job-grid">'+(list.length?list.map(jobCard).join(''):emptyState('아직 발견한 기회가 없어요',savedOnly?'추천 목록에서 북마크를 눌러 관심 기업을 저장해 보세요.':'검색어나 기업 유형을 바꿔보세요.',savedOnly?'/recommendations':null,'추천 둘러보기',3))+'</div></section><p class="demo-footnote">'+icon('file')+' 이 공고는 가상 데이터입니다. 점수는 서버의 규칙 기반 값이며 의미 유사도는 현재 0점입니다. 합격 가능성을 뜻하지 않습니다.</p>';
}
function emptyState(title, text, href, label, level=2) {
  return '<div class="empty-state"><span class="round-icon" aria-hidden="true">'+icon('leaf')+'</span><h'+level+'>'+title+'</h'+level+'><p>'+text+'</p>'+(href?'<a href="#'+href+'" class="btn">'+label+' '+icon('arrow')+'</a>':'<button class="btn outline" data-action="reset-filters">필터 초기화</button>')+'</div>';
}
function options(values, current, blank='') {
  // Keep a stored value that is no longer offered, so saving the form does not silently change it.
  const list = current && !values.includes(current) ? [...values, current] : values;
  return (blank ? '<option value="">'+blank+'</option>' : '')+list.map(v=>'<option '+(v===current?'selected':'')+'>'+esc(v)+'</option>').join('');
}
const emptyExperience = () => ({company:'', role:'백엔드 개발자', start:'', end:''});
function experienceFields(e,index) {
  return '<section class="experience-block" data-extra="'+esc(JSON.stringify(e.extra||{}))+'"><div class="project-heading"><h3>경력 '+(index+1)+'</h3><button class="btn text danger" type="button" data-action="remove-experience" '+(index===0?'hidden':'')+'>삭제</button></div><div class="form-row"><label>회사명 <span>*</span><input name="experienceCompany" required maxlength="200" placeholder="예: 온핏 주식회사" value="'+esc(e.company)+'"></label><label>담당 직무 <span>*</span><select name="experienceRole" required>'+options(roles,e.role)+'</select></label></div><div class="form-row"><label>시작 월 <span>*</span><input name="experienceStart" type="month" required value="'+esc(e.start)+'"></label><label>종료 월 <span class="optional">재직 중이면 비워 두세요</span><input name="experienceEnd" type="month" value="'+esc(e.end)+'"></label></div></section>';
}
function projectFields(p,index) {
  return '<section class="project-block" data-project="'+index+'" data-extra="'+esc(JSON.stringify(p.extra||{}))+'"><div class="project-heading"><h3>프로젝트 '+(index+1)+'</h3><button class="btn text danger" type="button" data-action="remove-project" '+(index===0?'hidden':'')+'>삭제</button></div><label>프로젝트명 <span>*</span><input name="projectName" required maxlength="100" placeholder="예: 개인 맞춤 채용 추천 서비스" value="'+esc(p.name)+'"></label><label>프로젝트 설명과 나의 역할 <span>*</span><textarea name="projectDescription" required maxlength="3000" rows="4" placeholder="어떤 문제를 해결했나요? 직접 맡은 역할과 성과를 알려주세요.">'+esc(p.description)+'</textarea></label><label>사용한 기술 스택 <span>*</span><input name="projectStack" required maxlength="500" placeholder="Java, Spring Boot, PostgreSQL처럼 쉼표로 구분해 주세요." value="'+esc(p.stack)+'"></label></section>';
}
function profilePage() {
  const p = profile || emptyProfile();
  return pageHeading('YOUR STORY STARTS HERE','당신의 이야기를 들려주세요.','작은 경험도 좋은 기회의 시작이 될 수 있어요. 언제든 수정할 수 있습니다.')+
  '<div class="profile-layout"><form id="profile-form" class="profile-form" aria-label="커리어 프로필"><section class="form-section" aria-labelledby="profile-step-1"><header class="form-section-heading"><span aria-hidden="true">01</span><div><h2 id="profile-step-1">어떤 기업에서 일하고 싶나요?</h2><p>기업 유형은 여러 개 선택할 수 있어요. 선택하지 않으면 전체를 고려해요.</p></div></header><fieldset class="type-options"><legend class="sr-only">선호 기업 유형</legend>'+['대기업','중견기업','스타트업'].map((t,i)=>'<label class="type-option"><input type="checkbox" name="types" value="'+t+'" '+(p.types.includes(t)?'checked':'')+'><span>'+icon(['briefcase','grid','leaf'][i])+'<strong>'+t+'</strong>'+icon('check')+'</span></label>').join('')+'</fieldset><label>관심 있는 기업 <span class="optional">선택</span><input name="companies" maxlength="500" value="'+esc(p.companies)+'" placeholder="기업명을 쉼표로 구분해 입력해 주세요."></label><p class="field-help">관심 기업명은 프로필에 저장되며, 현재 데모 점수에는 기업 유형만 반영됩니다.</p></section>'+
  '<section class="form-section" aria-labelledby="profile-step-2"><header class="form-section-heading"><span aria-hidden="true">02</span><div><h2 id="profile-step-2">어떤 일을 하고 싶나요?</h2><p>앞으로 나아가고 싶은 방향을 알려주세요.</p></div></header><div class="form-row"><label>목표 직무 <span>*</span><select name="role" required>'+options(roles,p.role)+'</select></label><label>목표 부서 <span class="optional">선택</span><input name="department" maxlength="100" value="'+esc(p.department)+'" placeholder="예: 플랫폼 개발팀, 금융 IT팀"></label><label>희망 지역 <span class="optional">선택</span><select name="location">'+options(regions,p.location,'선택 안 함')+'</select></label></div><fieldset class="career-options"><legend>경력 구분 <span>*</span></legend>'+['신입','경력'].map(c=>'<label><input type="radio" name="career" value="'+c+'" '+(p.career===c?'checked':'')+'> '+c+'</label>').join('')+'</fieldset><fieldset id="experience-field" class="experience-options" '+(p.career==='경력'?'':'hidden disabled')+'><legend>경력 사항 <span>*</span></legend><div id="experiences">'+(p.experiences?.length?p.experiences:[emptyExperience()]).map(experienceFields).join('')+'</div><button class="btn outline add-project" type="button" data-action="add-experience">'+icon('plus')+' 경력 추가</button></fieldset></section>'+
  '<section class="form-section" aria-labelledby="profile-step-3"><header class="form-section-heading"><span aria-hidden="true">03</span><div><h2 id="profile-step-3">어떤 경험을 쌓아왔나요?</h2><p>직접 만들고, 고민하고, 해결했던 경험을 적어주세요.</p></div></header><div id="projects">'+p.projects.map(projectFields).join('')+'</div><button class="btn outline add-project" type="button" data-action="add-project">'+icon('plus')+' 프로젝트 추가</button></section><div class="form-actions"><span>직무·지역·경력·기술·프로젝트는 서버에 저장됩니다. 기업 선호 등 일부 설정은 브라우저에 저장됩니다.</span><button class="btn" type="submit">프로필 저장하고 추천 보기 '+icon('arrow')+'</button></div></form>'+
  '<aside class="profile-guide" aria-labelledby="profile-guide-title"><span class="round-icon" aria-hidden="true">'+icon('spark')+'</span><h2 id="profile-guide-title">경험의 크기보다<br>나의 역할이 중요해요.</h2><p>팀 프로젝트, 개인 프로젝트,<br>새로운 시도 모두 좋아요.</p><figure class="guide-example"><figcaption><small>이렇게 적어보세요</small></figcaption><blockquote><p>“쇼핑몰 프로젝트에서 주문 API를 개발하고, 쿼리를 개선해 응답 시간을 줄였어요.”</p></blockquote></figure><ul aria-label="작성 포인트"><li>'+icon('check')+' 직접 맡은 역할</li><li>'+icon('check')+' 문제를 해결한 과정</li><li>'+icon('check')+' 사용한 기술과 얻은 결과</li></ul><div class="guide-bottom">온핏은 등록한 경험을 바탕으로<br>당신의 가능성을 찾아갑니다.</div></aside></div>';
}
function metric(label, result) {
  return '<div class="metric"><div><span>'+label+'</span><strong>'+(result.score===null?'확인 필요':result.score+'%')+'</strong></div><div class="meter" aria-hidden="true"><span style="width:'+(result.score??0)+'%"></span></div></div>';
}
function skillResult(skills, cls, emptyText) {
  return skills.length ? pillList('job-skills', skills.map(s=>[s, cls])) : '<div class="job-skills"><p class="subtle">'+emptyText+'</p></div>';
}
function analysisResult() {
  const title = '<h2 class="sr-only">적합도 분석 결과</h2>';
  if (!analysis) return title+'<div class="analysis-placeholder"><div class="analysis-illustration" aria-hidden="true">'+icon('file')+'<span>'+icon('spark')+'</span></div><h3>이 공고, 나와 얼마나 잘 맞을까요?</h3><p>공고를 붙여넣으면 등록한 기술과 비교해<br>잘 맞는 부분과 준비할 부분을 보여드려요.</p><ol class="placeholder-steps" aria-label="분석 순서"><li>01 공고 입력</li><li>'+icon('chevron')+'02 프로필 비교</li><li>'+icon('chevron')+'03 결과 확인</li></ol></div>';
  if (!analysis.all.length) return title+'<div class="analysis-placeholder"><span class="round-icon" aria-hidden="true">'+icon('search')+'</span><h3>비교할 기술을 찾지 못했어요.</h3><p>이 데모는 Java, React, Python 등 정해진 기술명을 인식해요.<br>기술 요구사항이 포함된 공고를 입력해 주세요.</p></div>';
  return title+'<article class="analysis-result"><div class="result-heading"><span class="eyebrow">YOUR FIT REPORT</span>'+pill('키워드 비교 데모')+'</div><div class="result-score"><div class="score-circle" style="--score:'+analysis.overall.score+'"><div><strong>'+analysis.overall.score+'<small>%</small></strong><span>기술 일치율</span></div></div><div><h3>나의 경험과<br>연결되는 지점을 찾았어요.</h3><p>인식한 기술 '+analysis.all.length+'개 중 '+analysis.overall.matched.length+'개 일치</p></div></div>'+metric('필수 기술 일치율',analysis.required)+metric('우대 기술 일치율',analysis.preferred)+'<section class="result-list"><h3>'+icon('check')+' 연결되는 기술</h3>'+skillResult(analysis.overall.matched,'matched','등록된 기술 중 일치하는 항목이 없어요.')+'</section><section class="result-list"><h3>'+icon('plus')+' 프로필에서 확인되지 않은 기술</h3>'+skillResult(analysis.overall.missing,'missing','인식된 기술은 모두 프로필에 있어요.')+'</section><p class="analysis-caveat" role="note">프로젝트의 깊이, 직무·부서 적합도, 경력 기간은 아직 평가하지 않습니다. 필수·우대 제목이 없는 항목은 전체 기술 일치율에만 반영됩니다. 미등록 기술은 실제 역량 부족을 의미하지 않습니다.</p></article>';
}

function currentJobText() { return inputMode==='file' ? attachmentText : jdText; }
function canAnalyze() {
  return currentJobText().trim().length >= 20 && (inputMode==='text' || (!!attachment && !attachmentBusy));
}
function attachmentMarkup() {
  if (!attachment) return '<button type="button" class="upload-zone" data-action="pick-file"><span class="round-icon">'+icon('plus')+'</span><strong>공고 사진이나 PDF를 올려주세요</strong><span>파일을 끌어다 놓거나 클릭해서 선택하세요.</span><small>JPG · PNG · WebP · PDF / 1개, 최대 10MB</small></button>';
  return '<div class="attachment-card"><div class="attachment-heading"><span class="round-icon">'+icon('file')+'</span><div><strong>'+esc(attachment.file.name)+'</strong><small>'+fileSize(attachment.file.size)+' · '+(attachment.kind==='pdf'?'PDF 문서':'공고 이미지')+'</small></div><button type="button" class="icon-btn" data-action="remove-file" aria-label="첨부 파일 삭제">'+icon('close')+'</button></div>'+
    (attachment.kind==='image' ? '<div class="image-preview"><img src="'+attachment.url+'" alt="첨부한 채용공고 미리보기"></div>' :
    '<iframe class="pdf-preview" src="'+attachment.url+'#toolbar=0" title="첨부한 채용공고 PDF 미리보기"></iframe>')+
    '<div class="attachment-actions"><button class="btn text small" type="button" data-action="pick-file">다른 파일 선택</button><a class="btn text small" href="'+attachment.url+'" target="_blank" rel="noopener">원본 열기 '+icon('arrow')+'</a></div>'+
    (attachment.kind==='pdf'?'<p class="upload-help">PDF가 보이지 않으면 원본 열기로 확인해 주세요.</p>':'')+'</div>';
}
function analyzePage() {
  if (!profile) return emptyState('비교할 프로필이 필요해요','프로젝트와 사용한 기술을 먼저 등록해 주세요.','/profile','프로필 작성하기',1);
  const text=currentJobText();
  const fileMode=inputMode==='file';
  return pageHeading('FIND YOUR FIT','이 기회, 나와 얼마나 잘 맞을까요?','공고를 붙여넣거나 사진·PDF를 첨부해 나의 경험과 연결해 보세요.')+
  '<div class="analysis-layout"><section class="panel jd-panel" aria-labelledby="jd-title"><header class="section-heading"><h2 id="jd-title">'+icon('file')+' 채용공고 입력</h2>'+(!fileMode?'<button class="btn text small" data-action="sample-jd">예시 불러오기</button>':'')+'</header>'+
  '<div class="input-mode-switch" role="group" aria-label="채용공고 입력 방식"><button data-action="input-mode" data-mode="text" aria-pressed="'+!fileMode+'" class="'+(!fileMode?'selected':'')+'">'+icon('file')+' 텍스트 입력</button><button data-action="input-mode" data-mode="file" aria-pressed="'+fileMode+'" class="'+(fileMode?'selected':'')+'">'+icon('plus')+' 사진 · PDF 첨부</button></div>'+
  '<form id="analysis-form">'+(fileMode?'<div id="attachment-zone" aria-busy="'+attachmentBusy+'"><input type="file" id="jd-file" class="sr-only" tabindex="-1" accept="'+FILE_ACCEPT+'" aria-label="채용공고 파일 선택">'+attachmentMarkup()+'</div><p id="attachment-error" class="upload-error" role="alert">'+esc(attachmentError)+'</p>'+
  '<div class="ocr-status" role="status"><span>'+icon('file')+'</span><div><strong>'+(attachmentBusy?'파일을 확인하고 있어요':'자동 텍스트 추출 · 연결 예정')+'</strong><p>한국어 OCR은 아직 연결되지 않았어요. 지금은 원본을 보며 아래에 공고 내용을 입력하면 텍스트 기준으로 분석할 수 있어요.</p></div></div>':'<p class="subtle input-help">담당 업무, 자격요건, 우대사항을 함께 넣어주세요.</p>')+
  '<label class="'+(fileMode?'review-label':'sr-only')+'" for="jd-text">'+(fileMode?'공고 텍스트 확인·수정':'채용공고 또는 Job Description')+'</label>'+
  '<textarea id="jd-text" name="jd" required minlength="20" maxlength="15000" '+(fileMode&&!attachment?'disabled':'')+' placeholder="'+(fileMode?'자동 추출은 아직 제공되지 않습니다. 첨부한 공고의 내용을 직접 입력해 주세요.':'분석할 공고를 붙여넣으세요.&#10;&#10;자격요건&#10;· Java, Spring Boot 기반의 개발 경험&#10;&#10;우대사항&#10;· Docker 기반 배포 경험')+'">'+esc(text)+'</textarea>'+
  '<div class="textarea-footer"><span>'+(fileMode?'입력·수정한 텍스트를 분석합니다.':'텍스트로 입력 · 최소 20자')+'</span><span id="jd-count">'+text.length.toLocaleString()+' / 15,000</span></div><button class="btn full" id="analyze-button" type="submit" '+(!canAnalyze()?'disabled':'')+'>'+icon('spark')+' 내 프로필과 비교하기</button></form>'+
  (fileMode?'<p class="upload-help">첨부 파일은 서버로 전송하지 않으며, 새로고침하면 사라집니다.</p>':'')+
  '<p class="jd-profile">'+icon('user')+'<span>'+esc(profile.role)+' · '+esc(profile.career)+' 프로필로 비교</span><a href="#/profile" aria-label="비교할 프로필 수정">수정</a></p></section><section class="panel result-panel" id="analysis-output" aria-live="polite">'+analysisResult()+'</section></div>';
}
function refreshAnalysisInput() {
  if (location.hash==='#/analyze') render();
}
function removeAttachment() {
  attachmentRequest++;
  if (attachment) URL.revokeObjectURL(attachment.url);
  attachment=null; attachmentText=''; attachmentError=''; attachmentBusy=false; analysis=null;
}
async function selectAttachment(files) {
  if (!files.length) return;
  const request=++attachmentRequest;
  if (files.length!==1) {
    attachmentBusy=false; attachmentError='공고 파일은 한 번에 1개씩 선택해 주세요.'; refreshAnalysisInput(); return;
  }
  attachmentBusy=true; attachmentError=''; refreshAnalysisInput();
  try {
    const file=files[0];
    const info=await validateAttachment(file);
    if(info.kind==='image') {
      const bitmap=await createImageBitmap(file);
      bitmap.close();
    }
    if(request!==attachmentRequest) return;
    const url=URL.createObjectURL(file.slice(0,file.size,info.mime));
    if (attachment) URL.revokeObjectURL(attachment.url);
    attachment={file,url,...info}; attachmentText=''; analysis=null;
  } catch(error) {
    if(request!==attachmentRequest) return;
    attachmentError=error instanceof DOMException?'이미지를 읽을 수 없어요. 다른 원본 파일을 선택해 주세요.':error.message;
  } finally {
    if(request===attachmentRequest) { attachmentBusy=false; refreshAnalysisInput(); }
  }
}
function render() {
  closeModal(false);
  let route = location.hash.slice(1) || '/';
  const allowed = ['/', ...navItems.map(n=>n[0])];
  if (!allowed.includes(route)) { navigate('/'); return; }
  if (route !== '/' && !demoSession) {
    app.innerHTML = landing();
    openLogin();
    return;
  }
  if (route === '/') app.innerHTML = landing();
  else {
    const content = route==='/profile'?profilePage():route==='/analyze'?analyzePage():recommendations(route==='/favorites');
    app.innerHTML = shell(route,content);
    document.querySelector('.app-footer p:last-child').textContent =
      '가상 공고와 서버 계산 추천을 사용하는 데모입니다. 실제 채용·인증 서비스는 아닙니다.';
    if (['/recommendations', '/favorites'].includes(route) && profile && !recommendationsLoaded && !recommendationsLoading && !recommendationError) {
      void loadRecommendations();
    }
  }
  document.title = (navItems.find(n=>n[0]===route)?.[2] || '나다운 커리어의 시작')+' | 온핏';
}
function openModal(content, label) {
  modalTrigger = document.activeElement;
  modalRoot.innerHTML = '<div class="modal-backdrop"><section class="modal" role="dialog" aria-modal="true" aria-label="'+label+'"><button class="icon-btn modal-close" data-action="close-modal" aria-label="닫기">'+icon('close')+'</button>'+content+'</section></div>';
  app.inert = true;
  document.body.classList.add('modal-open');
  modalRoot.querySelector('button')?.focus();
}
function closeModal(restore=true) {
  modalRoot.innerHTML = '';
  app.inert = false;
  document.body.classList.remove('modal-open');
  if (restore && modalTrigger?.isConnected) modalTrigger.focus();
}
function openLogin() {
  openModal('<div class="login-modal">'+logo()+'<span class="eyebrow">WELCOME TO YOUR NEXT CHAPTER</span><h2>나에게 맞는 기회,<br>온핏에서 시작해요.</h2><p>좋아하는 일에 한 걸음 더 가까이.</p><div class="oauth-buttons"><button disabled class="oauth google"><b>G</b> Google로 시작하기</button><button disabled class="oauth kakao"><b>●</b> 카카오로 시작하기</button><button disabled class="oauth naver"><b>N</b> 네이버로 시작하기</button></div><p class="oauth-note">소셜 로그인은 백엔드 연동 후 제공됩니다.<br>지금은 로그인 없이 화면을 체험할 수 있어요.</p><button class="btn full" data-action="blank-demo">내 프로필로 체험하기 '+icon('arrow')+'</button><button class="btn text full" data-action="sample-demo">샘플 프로필로 둘러보기</button><small class="privacy-note">입력한 프로필은 현재 브라우저에만 저장됩니다.</small></div>','로그인 및 데모 시작');
  modalRoot.querySelector('.privacy-note').textContent = '프로필은 공용 데모 사용자로 서버에 저장됩니다. 실제 개인정보를 입력하지 마세요.';
}
function openJob(id) {
  const matched = jobs.find(j=>j.id===id);
  if (!matched || !profile) return;
  const result = matched.scores;
  openModal('<article class="job-detail"><div class="company-logo '+matched.color+'" aria-hidden="true">'+esc(matched.initial)+'</div><div class="detail-eyebrow">'+esc(matched.company)+' · '+esc(matched.type)+' · 가상 공고</div><h2>'+esc(matched.title)+'</h2><p>'+esc(matched.intro)+'</p><div class="detail-meta">'+icon('pin')+esc(matched.location)+' · '+esc(matched.career)+' · 마감 '+esc(matched.deadline || '미정')+'</div><div class="detail-score"><div><small>서버 계산 추천 점수</small><strong>'+matched.score+'<span>점</span></strong></div><p>필수 35% · 우대 20% · 의미 20%<br>경험 15% · 희망조건 10% (의미 점수 0)</p></div><h3>함께 할 일</h3><p>'+esc(matched.duties)+'</p><h3>필수 기술</h3>'+pillList('job-skills', matched.required.map(s=>[s, matched.requiredMatch.matched.includes(s)?'matched':'missing']))+'<h3>우대 기술</h3>'+pillList('job-skills', matched.preferred.map(s=>[s, matched.preferredMatch.matched.includes(s)?'matched':'missing']))+'<h3>점수 상세</h3><p>필수 '+result.requiredScore+' · 우대 '+result.preferredScore+' · 의미 '+result.semanticScore+' · 경험 '+result.experienceScore+' · 희망조건 '+result.preferenceScore+'</p><h3>일치 근거</h3>'+(result.matchedEvidence.length?'<ul>'+result.matchedEvidence.map(e=>'<li>'+esc(e)+'</li>').join('')+'</ul>':'<p>일치 근거가 없습니다.</p>')+'<h3>확인할 필수 기술</h3>'+skillResult(result.missingSkills,'missing','누락된 필수 기술이 없습니다.')+'<div class="analysis-caveat" role="note">실제 채용 중인 공고가 아닌 가상 데이터입니다. 추천 점수는 합격 가능성이 아닙니다.</div><button class="btn full" data-action="favorite" data-id="'+matched.id+'">'+icon('bookmark')+(favorites.includes(matched.id)?'관심 기업에서 해제':'관심 기업에 저장')+'</button></article>','기업 추천 상세');
}
document.addEventListener('click', event => {
  const button = event.target.closest('[data-action]');
  if (event.target.classList.contains('modal-backdrop')) { closeModal(); return; }
  if (!button) return;
  const action = button.dataset.action;
  if (action==='input-mode') {
    inputMode=button.dataset.mode; analysis=null; render();
    document.querySelector('[data-action="input-mode"][data-mode="'+inputMode+'"]')?.focus();
  }
  if (action==='pick-file') document.querySelector('#jd-file')?.click();
  if (action==='remove-file') { removeAttachment(); render(); document.querySelector('[data-action="pick-file"]')?.focus(); }
  if (action==='login') openLogin();
  if (action==='close-modal') closeModal();
  if (action==='sample-demo') startDemo(true);
  if (action==='blank-demo') startDemo(false);
  if (action==='logout') { demoSession=false; persist('demoSession',false); navigate('/'); toast('데모에서 나왔어요. 공용 데모 프로필은 서버에 남아 있습니다.'); }
  if (action==='filter') { filter=button.dataset.value; render(); }
  if (action==='reset-filters') { filter='전체'; search=''; render(); }
  if (action==='job') openJob(button.dataset.id);
  if (action==='retry-recommendations') { recommendationError=''; void loadRecommendations(); render(); }
  if (action==='favorite') {
    const id=button.dataset.id;
    if (!jobs.some(j=>j.id===id)) return;
    const had = favorites.includes(id);
    const wasModal = !!modalRoot.querySelector('.job-detail');
    favorites = had?favorites.filter(i=>i!==id):[...favorites,id];
    persist('favorites',favorites);
    render();
    if (wasModal) openJob(id);
    toast(had?'관심 기업에서 해제했어요.':'관심 기업에 저장했어요.');
  }
  if (action==='add-project') {
    const holder=document.querySelector('#projects');
    holder.insertAdjacentHTML('beforeend',projectFields({name:'',description:'',stack:''},holder.children.length));
    holder.lastElementChild.querySelector('input').focus();
  }
  if (action==='remove-project') {
    button.closest('.project-block').remove();
    document.querySelectorAll('.project-block').forEach((p,i)=> { p.querySelector('h3').textContent='프로젝트 '+(i+1); });
  }
  if (action==='add-experience') {
    const holder=document.querySelector('#experiences');
    holder.insertAdjacentHTML('beforeend',experienceFields(emptyExperience(),holder.children.length));
    holder.lastElementChild.querySelector('input').focus();
  }
  if (action==='remove-experience') {
    button.closest('.experience-block').remove();
    document.querySelectorAll('.experience-block').forEach((e,i)=> { e.querySelector('h3').textContent='경력 '+(i+1); });
  }
  if (action==='sample-jd') {
    inputMode='text'; jdText=sampleJD; analysis=null;
    document.querySelector('#analyze-button').disabled=false;
    document.querySelector('#jd-text').value=jdText;
    document.querySelector('#jd-count').textContent=jdText.length.toLocaleString()+' / 15,000';
    document.querySelector('#analysis-output').innerHTML=analysisResult();
  }
});
document.addEventListener('input', event=>{
  if (event.target.id==='jd-text') {
    if (inputMode==='file') attachmentText=event.target.value; else jdText=event.target.value;
    analysis=null;
    document.querySelector('#analyze-button').disabled=!canAnalyze();
    document.querySelector('#jd-count').textContent=currentJobText().length.toLocaleString()+' / 15,000';
    document.querySelector('#analysis-output').innerHTML=analysisResult();
  }
  if (event.target.id==='company-search') {
    search=event.target.value;
    const position=event.target.selectionStart;
    render();
    const field=document.querySelector('#company-search'); field.focus(); field.setSelectionRange(position,position);
  }
  if (event.target.closest('#profile-form')) event.target.setCustomValidity?.('');
});
document.addEventListener('change', event=>{
  if (event.target.id==='jd-file') void selectAttachment([...event.target.files]);
  if (event.target.id==='sort') { sort=event.target.value; render(); }
  if (event.target.name==='career') {
    const field=document.querySelector('#experience-field');
    field.hidden=field.disabled=event.target.value==='신입';
  }
});
async function saveProfileFromForm(form) {
  const data=new FormData(form);
  const extra=block=>JSON.parse(block.dataset.extra||'{}');
  const projects=[...form.querySelectorAll('.project-block')].map(p=>({name:p.querySelector('[name=projectName]').value.trim(),description:p.querySelector('[name=projectDescription]').value.trim(),stack:p.querySelector('[name=projectStack]').value.trim(),extra:extra(p)}));
  if (projects.some(p=>!p.stack.split(/[,，\n]/).some(s=>s.trim()))) { toast('프로젝트에 기술을 한 개 이상 입력해 주세요.'); return; }
  const career=data.get('career');
  const experiences=career==='경력'?[...form.querySelectorAll('.experience-block')].map(e=>({company:e.querySelector('[name=experienceCompany]').value.trim(),role:e.querySelector('[name=experienceRole]').value,start:e.querySelector('[name=experienceStart]').value,end:e.querySelector('[name=experienceEnd]').value,extra:extra(e)})):[];
  if (experiences.some(e=>e.end && e.end<e.start)) { toast('경력 종료 월은 시작 월보다 빠를 수 없어요.'); return; }
  const next={types:data.getAll('types'), companies:data.get('companies').trim(), role:data.get('role'),
    location:data.get('location') || '', department:data.get('department').trim(),
    career, experiences, certificates:profile?.certificates || [], projects};
  try {
    const saved = await saveProfile(next);
    profile=fromProfileResponse(saved,next);
    persist('profile',profile);
    recommendationRequest++;
    recommendationsLoading=false; recommendationsLoaded=false; recommendationError=''; jobs=[];
    analysis=null; filter='전체'; search='';
    navigate('/recommendations');
    toast('프로필을 서버에 저장했어요. 추천을 계산합니다.');
  } catch (error) { toast(error.message); }
}
document.addEventListener('submit', event=>{
  if (event.target.id==='profile-form') {
    event.preventDefault();
    const form=event.target;
    for (const field of form.querySelectorAll('[required]:not(:disabled)')) {
      if (!field.value.trim()) { field.setCustomValidity('공백이 아닌 내용을 입력해 주세요.'); field.reportValidity(); return; }
    }
    void saveProfileFromForm(form);
  }
  if (event.target.id==='analysis-form') {
    event.preventDefault();
    if (!canAnalyze()) { toast('공고 텍스트를 20자 이상 입력해 주세요. 파일 모드에서는 파일도 필요합니다.'); return; }
    analysis=analyzeText(currentJobText(),profile);
    document.querySelector('#analysis-output').innerHTML=analysisResult();
  }
});
document.addEventListener('keydown', event=>{
  if (!modalRoot.children.length) return;
  if (event.key==='Escape') closeModal();
  if (event.key==='Tab') {
    const focusable=[...modalRoot.querySelectorAll('button:not([disabled]),a[href],input,textarea,select')];
    const first=focusable[0], last=focusable.at(-1);
    if (event.shiftKey && document.activeElement===first) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && document.activeElement===last) { event.preventDefault(); first.focus(); }
  }
});
document.addEventListener('dragover', event=>{
  if (!event.dataTransfer?.types.includes('Files')) return;
  event.preventDefault();
  const zone=event.target.closest('#attachment-zone');
  if(zone) { event.dataTransfer.dropEffect='copy'; zone.classList.add('drag-over'); }
});
document.addEventListener('dragleave', event=>{
  const zone=event.target.closest('#attachment-zone');
  if(zone&&!zone.contains(event.relatedTarget)) zone.classList.remove('drag-over');
});
document.addEventListener('drop', event=>{
  if (!event.dataTransfer?.types.includes('Files')) return;
  event.preventDefault();
  const zone=event.target.closest('#attachment-zone');
  zone?.classList.remove('drag-over');
  if(zone) void selectAttachment([...event.dataTransfer.files]);
});
window.addEventListener('pagehide', event=>{ if(!event.persisted) removeAttachment(); });
window.addEventListener('hashchange',()=>{filter='전체';search='';render();window.scrollTo(0,0);document.querySelector('#main-content')?.focus({preventScroll:true});});
render();
if (demoSession) {
  getProfile().then(response => {
    profile = fromProfileResponse(response, profile);
    persist('profile', profile);
    recommendationsLoaded = false;
    render();
  }).catch(error => {
    if (error.status === 404) { profile = null; render(); }
    else toast(error.message);
  });
}

