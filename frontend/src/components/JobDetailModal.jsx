import { Icon, PillList, SkillResult } from './ui.jsx';
import { Modal } from './Modal.jsx';

export function JobDetailModal({ job, saved, onToggleFavorite, onClose }) {
  const result = job.scores;
  const marks = (skills, matched) => skills.map(skill => [skill, matched.includes(skill) ? 'matched' : 'missing']);
  return (
    <Modal label="기업 추천 상세" onClose={onClose}>
      <article className="job-detail">
        <div className={'company-logo ' + job.color} aria-hidden="true">{job.initial}</div>
        <div className="detail-eyebrow">{job.company} · {job.industry}{job.companySize && ' · ' + job.companySize} · {job.origin === 'REAL' ? '실제 공고' : job.origin === 'SYNTHETIC' ? '가상 공고' : '출처 미확인 공고'}</div>
        <h2>{job.title}</h2>
        <p>{job.intro}</p>
        <div className="detail-meta"><Icon name="pin" />{job.location} · {job.career} · 마감 {job.deadline || '미정'}</div>
        {job.origin === 'REAL' && <p>출처: {job.sourceName} · 상태: {job.status}<br />
          최초 수집: {job.collectedAt ? new Date(job.collectedAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '미확인'}<br />
          최근 확인: {job.lastSeenAt ? new Date(job.lastSeenAt).toLocaleString('ko-KR', { timeZone: 'Asia/Seoul' }) : '미확인'}<br />
          {job.sourceUrl && <a href={job.sourceUrl} target="_blank" rel="noopener noreferrer">원문 공고 보기</a>}</p>}
        <div className="detail-score">
          <div><small>서버 계산 추천 점수</small><strong>{job.score}<span>점</span></strong></div>
          <p>필수 35% · 우대 20% · 의미 20%<br />경험 15% · 희망조건 10%</p>
        </div>
        <h3>함께 할 일</h3><p>{job.duties}</p>
        <h3>필수 기술</h3><PillList className="job-skills" items={marks(job.required, job.requiredMatch.matched)} />
        <h3>우대 기술</h3><PillList className="job-skills" items={marks(job.preferred, job.preferredMatch.matched)} />
        <h3>점수 상세</h3>
        <p>필수 {result.requiredScore} · 우대 {result.preferredScore} · 의미 {result.semanticScore} · 경험 {result.experienceScore} · 희망조건 {result.preferenceScore}</p>
        <h3>일치 근거</h3>
        {result.matchedEvidence.length
          ? <ul>{result.matchedEvidence.map(evidence => <li key={evidence}>{evidence}</li>)}</ul>
          : <p>일치 근거가 없습니다.</p>}
        <h3>확인할 필수 기술</h3>
        <SkillResult skills={result.missingSkills} className="missing" emptyText="누락된 필수 기술이 없습니다." />
        <div className="analysis-caveat" role="note">{job.origin === 'SYNTHETIC' ? '체험을 위한 가상 공고입니다. ' : '최신 채용 상태는 원문에서 확인해 주세요. '}추천 점수는 합격 가능성이 아닙니다.</div>
        <button className="btn full" data-action="favorite" data-id={job.id} onClick={() => onToggleFavorite(job.id)}>
          <Icon name="bookmark" />{saved ? '관심 기업에서 해제' : '관심 기업에 저장'}
        </button>
      </article>
    </Modal>
  );
}
