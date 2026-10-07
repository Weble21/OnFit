import { profileSkills } from '../data.js';
import { EmptyState, Icon, PageHeading, Pill, PillList, Plant } from '../components/ui.jsx';

function JobCard({ job, saved, onToggleFavorite, onOpen }) {
  const matched = job.requiredMatch.matched;
  return (
    <article className="job-card">
      <div className="job-card-top">
        <span className={'company-logo ' + job.color} aria-hidden="true">{job.initial}</span>
        <button className={'icon-btn bookmark ' + (saved ? 'saved' : '')} data-action="favorite" data-id={job.id}
          aria-label={job.company + (saved ? ' 관심 기업 해제' : ' 관심 기업 저장')} aria-pressed={saved}
          onClick={() => onToggleFavorite(job.id)}><Icon name="bookmark" /></button>
      </div>
      <div className="company-line"><h3>{job.company}</h3><Pill>{job.industry}</Pill><Pill>{job.origin === 'REAL' ? '실제 공고' : job.origin === 'SYNTHETIC' ? '가상 공고' : '출처 미확인'}</Pill></div>
      <p className="job-title">{job.title}</p>
      <p className="job-meta">
        <Icon name="pin" />{job.location}<span aria-hidden="true">·</span>{job.career}
        {job.companySize && <><span aria-hidden="true">·</span>{job.companySize}</>}
      </p>
      <PillList className="job-skills" items={job.required.map(skill => [skill, matched.includes(skill) ? 'matched' : ''])} />
      <p className="match-reason"><Icon name="spark" /><span>{matched.length ? matched.slice(0, 2).join(', ') + ' 기술이 일치해요.' : '필수 기술을 확인해 보세요.'}</span></p>
      <footer className="job-card-bottom">
        <div className="card-score"><strong>{job.score}<small>점</small></strong><span>추천 점수</span></div>
        <button className="btn text job-card-open" data-action="job" data-id={job.id} aria-label={job.company + ' 공고 자세히 보기'}
          onClick={() => onOpen(job.id)}>자세히 보기 <Icon name="arrow" /></button>
      </footer>
    </article>
  );
}

export function Recommendations({ savedOnly, profile, recs, favorites, filters, onFiltersChange, onRetry, onToggleFavorite, onOpenJob }) {
  if (!profile) {
    return <EmptyState title="먼저 나의 이야기를 들려주세요" text="프로필을 작성하면 경험에 맞는 기업을 찾아볼 수 있어요." href="/profile" label="프로필 작성하기" level={1} />;
  }
  if (recs.status === 'error') {
    return (
      <div className="empty-state" role="alert">
        <h1>추천을 불러오지 못했어요</h1><p>{recs.error}</p>
        <button className="btn" data-action="retry-recommendations" onClick={onRetry}>다시 시도</button>
      </div>
    );
  }
  if (recs.status !== 'ready') {
    return <div className="empty-state" role="status"><h1>추천을 계산하고 있어요</h1><p>저장된 프로필과 공고를 비교하고 있습니다.</p></div>;
  }

  const { filter, sort, search } = filters;
  const jobs = recs.jobs;
  const query = search.toLowerCase();
  const list = jobs
    .filter(job => (!savedOnly || favorites.includes(job.id)) && (filter === '전체' || job.industry === filter)
      && (job.company + ' ' + job.title + ' ' + job.required.join(' ')).toLowerCase().includes(query))
    .sort(sort === 'name' ? (a, b) => a.company.localeCompare(b.company, 'ko') : (a, b) => b.score - a.score || Number(a.id) - Number(b.id));
  // Industries the profile is interested in come first.
  const industries = ['전체', ...[...new Set(jobs.map(job => job.industry))].sort((a, b) => profile.types.includes(b) - profile.types.includes(a))];
  const skills = profileSkills(profile);
  const resetFilters = () => onFiltersChange({ filter: '전체', search: '' });

  return (
    <>
      <PageHeading kicker={savedOnly ? 'YOUR COLLECTION' : 'DISCOVER YOUR POSSIBILITIES'}
        title={savedOnly ? '마음에 담아둔 기회' : '나의 경험이, 다음 기회로.'}
        description={savedOnly ? '관심 있는 기업을 모아두고 차근차근 준비해 보세요.' : '지금까지 쌓아온 경험을 알아봐 줄 기업들을 만나보세요.'}>
        <a className="btn outline small" href="#/profile"><Icon name="user" /> 프로필 수정</a>
      </PageHeading>
      {!savedOnly && <>
        <section className="welcome-banner" aria-labelledby="welcome-title">
          <div>
            <span className="banner-label">YOUR NEXT CHAPTER</span>
            <h2 id="welcome-title">나에게 맞는 곳에서,<br />더 크게 자라날 수 있도록.</h2>
            <p>등록한 기술과 희망 직무로 {jobs.length}개의 공고를 비교했어요.</p>
            <a href="#/analyze" className="banner-link">눈여겨본 공고가 있나요? 직접 분석하기 <Icon name="arrow" /></a>
          </div>
          <Plant />
        </section>
        <section className="profile-strip" aria-label="나의 커리어 키워드">
          <div className="profile-strip-title">
            <span className="round-icon" aria-hidden="true"><Icon name="user" /></span>
            <div><small>나의 커리어 키워드</small><strong>{profile.role} <span>· {profile.career}</span></strong></div>
          </div>
          <PillList className="profile-tags" items={[...skills.slice(0, 4).map(skill => [skill]), ...(skills.length > 4 ? [['+' + (skills.length - 4)]] : [])]} />
          <a href="#/profile" className="icon-btn" aria-label="커리어 프로필 수정"><Icon name="chevron" /></a>
        </section>
      </>}
      <section className="recommendation-section" aria-labelledby="recommendation-title">
        <header className="section-heading">
          <h2 id="recommendation-title">{savedOnly ? '저장한 기업' : '발견한 기회'} <span>{list.length}<span className="sr-only">곳</span></span></h2>
          <span className="subtle"><Icon name="spark" /> 서버 계산 추천</span>
        </header>
        <div className="filters">
          <div className="filter-tabs" role="group" aria-label="업종">
            {industries.map(industry => (
              <button key={industry} className={filter === industry ? 'selected' : ''} data-action="filter" data-value={industry}
                aria-pressed={filter === industry} onClick={() => onFiltersChange({ filter: industry })}>{industry}</button>
            ))}
          </div>
          <search className="filter-controls">
            <label className="search-field"><Icon name="search" />
              <input id="company-search" aria-label="기업 또는 기술 검색" placeholder="기업·기술 검색" value={search}
                onChange={event => onFiltersChange({ search: event.target.value })} />
            </label>
            <select id="sort" aria-label="추천 정렬" value={sort} onChange={event => onFiltersChange({ sort: event.target.value })}>
              <option value="score">점수순</option><option value="name">기업명순</option>
            </select>
          </search>
        </div>
        <div className="job-grid">
          {list.length
            ? list.map(job => <JobCard key={job.id} job={job} saved={favorites.includes(job.id)} onToggleFavorite={onToggleFavorite} onOpen={onOpenJob} />)
            : <EmptyState title="아직 발견한 기회가 없어요" level={3}
                text={savedOnly ? '추천 목록에서 북마크를 눌러 관심 기업을 저장해 보세요.' : '검색어나 기업 유형을 바꿔보세요.'}
                href={savedOnly ? '/recommendations' : null} label="추천 둘러보기" onReset={resetFilters} />}
        </div>
      </section>
      <p className="demo-footnote"><Icon name="file" /> 공고마다 실제·가상 여부와 출처를 확인해 주세요. 추천 점수는 합격 가능성을 뜻하지 않습니다.</p>
    </>
  );
}
