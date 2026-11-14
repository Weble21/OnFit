import { useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { FILE_ACCEPT, fileSize, pastedFile } from '../upload.js';
import { EmptyState, Icon, PageHeading, Pill, SkillResult } from '../components/ui.jsx';

const hasFiles = event => event.dataTransfer?.types.includes('Files');

const SEMANTIC_NOTE = {
  DISABLED: '의미 유사도 모델을 사용하지 않아 이 항목(20%)은 0점으로 계산되며, 총점은 최대 80점입니다.',
  FAILED: '의미 유사도 계산에 실패해 이 항목(20%)은 0점으로 계산되었고, 총점은 최대 80점입니다.',
};
const POSTING_FIELDS = [['companyName', '회사'], ['title', '공고명'], ['roleName', '직무'], ['careerLevel', '경력'], ['location', '근무지']];
// Brief sections list short phrases only; their quotes are still checked by the AI service, just not shown.
const ROLE_SECTIONS = [
  ['responsibilities', '핵심 업무', true], ['competencies', '핵심 요구 역량', true],
  ['strengths', '내 강점', false], ['unverified', '프로필에서 확인되지 않은 역량', true],
];
const formatScore = value => Number(value).toFixed(Number.isInteger(Number(value)) ? 0 : 1);
const scoreRange = rubric => `${formatScore(rubric.lowerTotal)}~${formatScore(rubric.upperTotal)}점`;
const evidenceSummary = rubric => {
  const criteria = rubric.categories.filter(category => category.key !== 'userPreference').flatMap(category => {
    const weights = category.criteria.reduce((sum, item) => sum + item.weight, 0);
    return category.criteria.map(item => ({ ...item, impact: category.weight * item.weight / weights }));
  });
  const unique = items => [...new Set(items.map(item => item.requirement))].slice(0, 2).join(', ');
  return {
    confirmed: unique(criteria.filter(item => item.level >= 3 && item.profileEvidence)),
    needed: unique(criteria.filter(item => item.level == null).sort((a, b) => b.impact - a.impact)),
  };
};
const scoreMeaning = value => value >= 85 ? '직접 수행 경험이 중심인 높은 적합도'
  : value >= 65 ? '관련 경험이 충분하며 일부 보완 필요'
    : value >= 40 ? '기초·관련 경험이 혼재하며 추가 입증 또는 학습 필요'
      : '현재 자료에서 직무와 연결되는 근거가 제한적';

function Metric({ label, value, note }) {
  return (
    <div className="metric">
      <div><span>{label}</span><strong>{value === null ? note : formatScore(value) + '점'}</strong></div>
      <div className="meter" aria-hidden="true"><span style={{ width: (value ?? 0) + '%' }}></span></div>
    </div>
  );
}

function RubricResult({ rubric }) {
  const summary = evidenceSummary(rubric);
  const questions = [...rubric.categories.flatMap(category => category.criteria.filter(item => item.level == null).map(item => `${item.requirement} 관련 경험이나 증빙을 프로필에 추가해 주세요.`)),
    ...rubric.eligibilityNotes.map(note => `${note}에 대한 증빙이 있나요?`)]
    .filter((item, index, all) => all.indexOf(item) === index).slice(0, 3);
  return <section className="rubric-result" aria-label="항목별 적합도 점수">
    <div className="rubric-heading"><strong>항목별 평가</strong><span>적용 배점 {rubric.appliedWeight}/100 · N/A 항목 제외 후 재환산</span></div>
    <div className="rubric-grid">{rubric.categories.map(category => <div key={category.key} className="rubric-cell">
      <span>{category.label}</span><strong>{!category.criteria.length ? 'N/A' : category.points == null
        ? '추가 확인 필요'
        : `${formatScore(category.points)} / ${category.weight}`}</strong>
    </div>)}</div>
    {rubric.pending && <div className="rubric-questions">
      <strong>프로필에서 확인한 내용</strong>
      <p>{summary.confirmed ? `확인된 근거: ${summary.confirmed}` : '직무 요구사항과 연결되는 구체적인 수행 경험을 더 확인해야 해요.'}</p>
      {summary.needed && <p>먼저 보완할 근거: {summary.needed}</p>}
    </div>}
    {rubric.total != null && <p className="rubric-meta">{scoreMeaning(Number(rubric.total))} · 제출자료 기준 점수이며 합격 확률은 아닙니다.</p>}
    {!rubric.pending && rubric.appliedWeight < 50 && <p className="rubric-limitation">평가할 수 있는 기준이 적어 재환산 총점이 높게 보일 수 있습니다.</p>}
    <p className="rubric-meta">필수조건 <strong>{rubric.eligibility}</strong></p>
    {rubric.eligibilityNotes.length > 0 && <ul className="rubric-notes">{rubric.eligibilityNotes.map(note => <li key={note}>{note}</li>)}</ul>}
    {questions.length > 0 && <div className="rubric-questions"><strong>추가 확인 사항</strong><ol>{questions.map(question => <li key={question}>{question}</li>)}</ol></div>}
    <details className="rubric-details"><summary>평가 기준과 근거 보기</summary>
      {!!rubric.conditions?.length && <div><h4>지원조건 판정</h4><ul>{rubric.conditions.map((condition, index) => <li key={index}>
        <strong>{condition.requirement}</strong> · {condition.level === 4 ? '충족' : condition.level === 0 ? '미충족' : '확인 필요'}
        <small>공고: {condition.postingEvidence}</small>
        <small>프로필: {condition.profileEvidence || '확인된 근거 없음'}</small>
        {condition.reason && <small>판정 이유: {condition.reason}</small>}
      </li>)}</ul></div>}
      {rubric.pending && <p className="rubric-limitation">미확인 항목은 현재 자료에서 입증된 점수를 부여하지 않았습니다. 실제 역량 부족과 구분해 기록하며, 프로필을 보완하면 점수와 순위가 달라질 수 있습니다.</p>}
      <p className="rubric-meta">근거 충실도: {rubric.evidenceCoverage == null ? '평가 불가' : `${formatScore(rubric.evidenceCoverage)}% (${rubric.evidenceLevel})`}</p>
      {rubric.categories.filter(category => category.criteria.length).map(category => <div key={category.key}>
        <h4>{category.label}</h4>
        <ul>{category.criteria.map((criterion, index) => <li key={index}>
          <strong>{criterion.requirement}</strong> · 수준 {criterion.level == null ? '미확인' : `${criterion.level}/4`} · {criterion.status}
          <small>공고: {criterion.postingEvidence}</small>
          <small>프로필: {criterion.profileEvidence || '확인된 근거 없음'}</small>
          {criterion.reason && <small>판정 이유: {criterion.reason}</small>}
        </li>)}</ul>
      </div>)}
    </details>
  </section>;
}

/** Quotes are shown with each claim so the user can check it against the posting or their own profile. */
function Evidence({ item, labels }) {
  const quotes = item.evidence
    ? item.evidence.map(quote => ['공고', quote])
    : item.profileEvidence.map(ref => [labels.get(ref.itemId) || '프로필', ref.quote]);
  return (
    <details className="evidence">
      <summary>근거 {quotes.length}개</summary>
      <ul>{quotes.map(([source, quote], i) => <li key={i}><span>{source}</span><q>{quote}</q></li>)}</ul>
    </details>
  );
}

function RoleAnalysis({ analysis, retrying, onRetry }) {
  if (analysis.roleStatus !== 'COMPLETED') {
    return (
      <section className="result-list role-failed" aria-labelledby="role-title">
        <h3 id="role-title"><Icon name="spark" /> 직무 분석</h3>
        <p className="upload-error" role="alert">{analysis.roleError || '직무 분석에 실패했습니다.'} 점수 결과는 그대로 유지됩니다.</p>
        <button type="button" className="btn outline small" data-action="retry-role" onClick={onRetry} disabled={retrying}>
          {retrying ? '직무 분석 중…' : '직무 분석 다시 시도'}
        </button>
      </section>
    );
  }
  const role = analysis.roleAnalysis;
  const labels = new Map(analysis.profileItems.map(item => [item.id, item.label]));
  return <>
    <section className="result-list" aria-labelledby="role-summary">
      <h3 id="role-summary"><Icon name="spark" /> 직무 한줄 요약</h3>
      {role.summary
        ? <div className="role-item"><p>{role.summary.text}</p><Evidence item={role.summary} labels={labels} /></div>
        : <p className="subtle">공고에서 근거를 찾을 수 있는 요약을 만들지 못했어요.</p>}
    </section>
    {ROLE_SECTIONS.map(([key, label, brief]) => (
      <section className="result-list" key={key} aria-labelledby={'role-' + key}>
        <h3 id={'role-' + key}><Icon name={key === 'unverified' ? 'plus' : 'check'} /> {label}</h3>
        {!role[key].length
          ? <p className="subtle">{key === 'strengths' ? '프로필에서 근거가 되는 항목을 찾지 못했어요.' : '해당 항목이 없어요.'}</p>
          : brief
            ? <ul className="brief-items">{role[key].map((item, i) => <li key={i}>{item.text}</li>)}</ul>
            : <ul className="role-items">{role[key].map((item, i) => <li className="role-item" key={i}><p>{item.text}</p><Evidence item={item} labels={labels} /></li>)}</ul>}
        {key === 'unverified' && <p className="subtle role-note">프로필에 적혀 있지 않다는 뜻이며, 역량이 없다는 뜻은 아닙니다.</p>}
      </section>
    ))}
    {role.highlights?.length > 0 && <section className="result-list" aria-labelledby="role-highlights">
      <h3 id="role-highlights"><Icon name="check" /> 지원서·면접에서 강조할 경험</h3>
      <ul className="role-items">{role.highlights.map((item, i) => <li className="role-item" key={i}>
        <p>{item.text}</p><Evidence item={item} labels={labels} />
      </li>)}</ul>
    </section>}
  </>;
}

function AnalysisResult({ state }) {
  const { analysis, analyzing, analysisError } = state;
  const title = <h2 className="sr-only">적합도 분석 결과</h2>;
  if (analyzing) {
    const progress = state.comparisonMode ? state.comparisonProgress : null;
    const completed = progress?.completed ?? 0;
    const total = progress?.total ?? 0;
    const ringOffset = total > 0 ? 276.46 * (1 - Math.min(completed / total, 1)) : 0;
    return <>{title}<div className="analysis-placeholder" aria-busy="true">
      <div className={'analysis-progress' + (total ? '' : ' analysis-progress-indeterminate')}
        role="progressbar" aria-label="분석 진행 상태" aria-valuemin={total ? 0 : undefined}
        aria-valuemax={total || undefined} aria-valuenow={total ? completed : undefined}
        aria-valuetext={total ? `${total}개 직무 중 ${completed}개 처리 완료` : '분석 중'}>
        <svg viewBox="0 0 100 100" aria-hidden="true">
          <circle className="analysis-progress-track" cx="50" cy="50" r="44" />
          <circle className="analysis-progress-fill" cx="50" cy="50" r="44" style={total ? { strokeDashoffset: ringOffset } : undefined} />
        </svg>
        <span className="analysis-progress-center" aria-hidden="true">{total ? <strong>{completed}<small>/{total}</small></strong> : <Icon name="spark" />}</span>
      </div>
      <h3>공고를 구조화하고 프로필과 비교하고 있어요.</h3>
      <p>AI 구조화와 직무 분석까지 시간이 걸릴 수 있어요.<br />텍스트를 수정하면 진행 중인 분석은 취소됩니다.</p>
    </div></>;
  }
  if (analysisError) {
    return <>{title}<div className="analysis-placeholder">
      <span className="round-icon" aria-hidden="true"><Icon name="close" /></span>
      <h3>분석하지 못했어요.</h3>
      <p className="upload-error" role="alert">{analysisError}</p>
    </div></>;
  }
  if (!analysis) {
    if (state.comparison?.length) return <>{title}<div className="analysis-placeholder"><h3>비교할 결과를 만들지 못했어요.</h3><p>직무별 오류를 확인하고 다시 시도해 주세요.</p></div></>;
    return <>{title}<div className="analysis-placeholder">
      <div className="analysis-illustration" aria-hidden="true"><Icon name="file" /><span><Icon name="spark" /></span></div>
      <h3>이 공고, 나와 얼마나 잘 맞을까요?</h3>
      <p>공고를 넣으면 AI가 요구사항을 정리하고<br />등록한 프로필과 비교해 맞춤 점수와 직무 분석을 보여드려요.</p>
      <ol className="placeholder-steps" aria-label="분석 순서"><li>01 공고 입력</li><li><Icon name="chevron" />02 구조화·점수 계산</li><li><Icon name="chevron" />03 직무 분석</li></ol>
    </div></>;
  }
  const { score, posting } = analysis;
  const rubric = score.rubric;
  const semanticOk = score.semanticStatus === 'OK';
  const legacyScoring = !rubric && !['rules-v1/analysis-web-duty/adapter-v5', 'rules-v1/analysis-certificates/adapter-v6', 'rules-v1/preference-items/adapter-v7'].includes(analysis.versions.calculator);
  const primarySkills = posting.requiredSkills.length ? posting.requiredSkills : (posting.dutySkills || []);
  const skillBasis = posting.requiredSkills.length ? '필수 기술' : '업무 기술';
  const insufficient = score.noRequirements;
  const pending = !!rubric?.pending;
  const relatedRequirements = new Set((score.relatedMatches || []).map(item => item.requirement));
  return <>{title}<article className="analysis-result">
    <div className="result-heading"><span className="eyebrow">YOUR FIT REPORT · {analysis.targetRole}</span><Pill>AI 구조화 · 규칙 기반 점수</Pill></div>
    {analysis.stale && <p className="upload-error" role="alert">이 결과 이후 프로필이 바뀌었어요. 다시 비교하면 새 프로필로 계산합니다.</p>}
    {analysis.roleStatus === 'FAILED' && <p className="upload-error" role="alert">AI 프로필 평가에 실패했습니다. 아래 점수와 지원조건은 기본 규칙으로 계산한 임시 결과입니다. 다시 분석해 주세요.</p>}
    <div className="result-score">
      <div className="score-circle" style={{ '--score': insufficient ? 0 : score.total / score.maxTotal * 100 }}><div><strong>{insufficient ? '—' : formatScore(score.total)}{!insufficient && <small>점</small>}</strong><span>{insufficient ? '정보 부족' : '직무 적합도'}</span></div></div>
      <div>
        <h3>{insufficient ? '공고에서 평가 기준을 찾지 못했어요.' : '제출한 프로필을 기준으로 평가했어요.'}</h3>
        <p>{insufficient ? '공고의 업무와 자격요건이 포함되어 있는지 확인해 주세요.' : rubric ? `100점 만점 · 신입·경력 수준에 맞춘 경험 평가` : <>{score.maxTotal === 100 ? '100점 만점' : '현재 최대 ' + formatScore(score.maxTotal) + '점'} · {skillBasis} {score.matchedRequired.length}/{primarySkills.length}개 일치·관련</>}</p>
      </div>
    </div>
    {rubric ? <RubricResult rubric={rubric} /> : <>
    <Metric label={skillBasis + " (" + (score.requiredWeight ?? 40) + "%)"} value={primarySkills.length ? score.required : null} note="비교 기준 없음" />
    {(score.dutyWeight ?? 0) > 0 && <Metric label={"업무 기술 (" + score.dutyWeight + "%)"} value={score.duty} note="비교 기준 없음" />}
    <Metric label="우대사항 (20%)" value={score.preferredCriteria?.length || posting.preferredSkills.length || score.preferredCertificates?.length ? score.preferred : null} note="비교할 우대사항 없음" />
    {legacyScoring && <Metric label="의미 유사도 (20%)" value={semanticOk ? score.semantic : null} note={score.semanticStatus === 'FAILED' ? '계산 실패' : '사용 안 함'} />}
    <Metric label={'경력·프로젝트 연관 (' + (legacyScoring ? 20 : 30) + '%)'} value={insufficient ? null : score.experience} note="평가 정보 부족" />
    {!posting.requiredSkills.length && primarySkills.length > 0 && <p className="subtle score-note">필수 기술이 별도로 명시되지 않아 담당 업무에 나온 기술을 비교했습니다. 필수 자격요건을 뜻하지 않습니다.</p>}
    {legacyScoring && <p className="subtle score-note">이전 점수 기준으로 계산된 결과입니다. 다시 비교하면 의미 유사도를 제외한 새 기준이 적용됩니다.</p>}
    {legacyScoring && !semanticOk && <p className="subtle score-note">{SEMANTIC_NOTE[score.semanticStatus]}</p>}
    </>}
    {score.requiredCertificates?.length > 0 && <section className="result-list">
      <h3><Icon name="file" /> 필수 자격증</h3>
      {score.requiredCertificates.map((item, i) => <div className="role-item" key={i}>
        <p>{item.condition} · {item.matched ? '프로필에서 확인: ' + item.matchedCertificates.join(', ') : rubric ? '프로필에서 확인되지 않음 · 지원조건 확인 필요' : '프로필에서 확인되지 않음 · 총점 0점'}</p>
        <Evidence item={{ evidence: [item.evidence] }} labels={new Map()} />
      </div>)}
      <p className="subtle">프로필에 등록된 자격증을 기준으로 비교합니다. 실제 지원 자격은 공고 원문에서 확인해 주세요.</p>
    </section>}
    {score.preferredCriteria?.length > 0 && <section className="result-list">
      <h3><Icon name="check" /> 우대사항 비교</h3>
      <p className="subtle">공고의 우대 항목을 각각 같은 비중으로 비교합니다. 프로필에 명확한 근거가 없으면 확인되지 않은 항목으로 표시합니다.</p>
      {score.preferredCriteria.map((item, i) => <div className="role-item" key={i}>
        <p>{item.condition} · {item.matched ? '확인: ' + item.profileEvidence.join(', ') : '프로필에서 확인되지 않음'}</p>
        <Evidence item={{ evidence: [item.evidence] }} labels={new Map()} />
      </div>)}
    </section>}
    {!score.preferredCriteria?.length && score.preferredCertificates?.length > 0 && <section className="result-list">
      <h3><Icon name="check" /> 우대 자격증</h3>
      {score.preferredCertificates.map((item, i) => <div className="role-item" key={i}>
        <p>{item.condition} · {item.matched ? '프로필에서 확인: ' + item.matchedCertificates.join(', ') : '프로필에서 확인되지 않음'}</p>
        <Evidence item={{ evidence: [item.evidence] }} labels={new Map()} />
      </div>)}
    </section>}
    <section className="result-list"><h3><Icon name="check" /> 일치하는 기술</h3><SkillResult skills={[...new Set([...score.matchedRequired, ...score.matchedPreferred, ...(score.matchedDuty || [])])].filter(item => !relatedRequirements.has(item))} className="matched" emptyText={relatedRequirements.size ? '정확히 일치한 기술은 없으며, 아래에 관련 기술 근거가 있어요.' : '등록된 기술 중 일치하는 항목이 없어요.'} /></section>
    {(score.matchedDuty?.length > 0 || score.missingDuty?.length > 0) && <section className="result-list">
      <h3><Icon name="file" /> 공고 업무 기술 비교</h3>
      <p className="subtle">업무 기술은 공고의 담당 업무에 나온 기술이며, 필수 자격요건을 뜻하지 않습니다.</p>
      <SkillResult skills={(score.matchedDuty || []).filter(item => !relatedRequirements.has(item))} className="matched" emptyText="일치하는 업무 기술이 없어요." />
      {score.missingDuty?.length > 0 && <><p className="subtle">프로필에서 확인되지 않은 업무 기술</p>
        <SkillResult skills={score.missingDuty} className="missing" emptyText="" /></>}
    </section>}
    {score.relatedMatches?.length > 0 && <section className="result-list">
      <h3><Icon name="check" /> 관련 기술 근거</h3>
      <p className="subtle">공고의 기술과 프로필의 관련 기술을 연결한 근거이며, 같은 기술을 직접 사용했다는 뜻은 아닙니다.</p>
      <ul className="role-items">{score.relatedMatches.map((item, i) =>
        <li className="role-item" key={i}><p>{item.requirement} · {item.evidence.join(', ')}</p></li>)}</ul>
    </section>}
    <section className="result-list"><h3><Icon name="plus" /> 프로필에서 확인되지 않은 {skillBasis}</h3><SkillResult skills={score.missing} className="missing" emptyText={insufficient ? '공고에서 비교할 기술을 확인하지 못했어요.' : '비교한 기술은 모두 프로필에 있어요.'} /></section>
    <section className="result-list" aria-labelledby="posting-title">
      <h3 id="posting-title"><Icon name="file" /> AI가 정리한 공고</h3>
      {/^https?:\/\//i.test(analysis.sourceUrl || '') && <a className="btn text small source-link" href={analysis.sourceUrl} target="_blank" rel="noopener noreferrer">원문 보기 <Icon name="arrow" /></a>}
      <dl className="posting-facts">{POSTING_FIELDS.map(([key, label]) => <div key={key}><dt>{label}</dt><dd>{posting[key].value || '공고에 없음'}</dd></div>)}</dl>
      {posting.ambiguities.length > 0 && <ul className="role-items">{posting.ambiguities.map((item, i) => <li className="role-item" key={i}><p>모호한 조건: {item.text}</p><Evidence item={{ evidence: [item.evidence] }} labels={new Map()} /></li>)}</ul>}
    </section>
    <RoleAnalysis analysis={analysis} retrying={state.retrying} onRetry={state.retryRole} />
    <p className="analysis-caveat" role="note">AI가 프로필 근거로 수행 수준과 지원조건을 평가하고, 서버가 정해진 배점으로 점수를 계산합니다. 제출자료 기준 점수이며 합격 확률은 아닙니다. 프로필에 없는 기술은 실제 역량 부족을 의미하지 않습니다.</p>
    <details className="analysis-versions"><summary>계산 정보</summary>
      <p>구조화 {analysis.versions.extractionModel} · {analysis.versions.extractionPrompt} / 계산기 {analysis.versions.calculator}{legacyScoring && ' / 의미 유사도 ' + analysis.versions.semanticModel}{analysis.versions.roleModel && ' / 직무 분석 ' + analysis.versions.roleModel + ' · ' + analysis.versions.rolePrompt}</p>
    </details>
  </article></>;
}

function ComparisonResults({ state }) {
  if (!state.comparison?.length || state.analyzing) return <AnalysisResult state={state} />;
  const hasErrors = state.comparison.some(item => item.error);
  const hasOtherUnranked = state.comparison.some(item => item.rank == null && !item.error);
  const first = state.comparison.find(item => item.rank === 1);
  const category = (item, key) => {
    const found = item.result?.score.rubric?.categories?.find(part => part.key === key);
    return !found?.criteria?.length ? 'N/A' : found.points == null
      ? `${formatScore(found.lowerPoints)}~${formatScore(found.upperPoints)}` : formatScore(found.points);
  };
  return <>
    <div className="comparison-results">
      <span className="eyebrow">ROLE COMPARISON</span>
      <h2>직무별 적합도 비교</h2>
      <p className="subtle">입력한 직무를 같은 프로필로 각각 분석한 결과입니다. 적용 배점이 다를 수 있으니 점수와 근거를 함께 확인해 주세요. 점수는 합격 확률이 아닙니다.</p>
      {first && <p className="comparison-conclusion">제출자료 기준 적합도 1위는 <strong>{first.role}</strong>입니다. 직무를 선택하면 항목별 점수와 근거를 확인할 수 있어요.</p>}
      {hasErrors && <p className="upload-error" role="status">일부 직무 분석에 실패했습니다. 해당 직무의 오류 내용을 확인해 주세요.</p>}
      {hasOtherUnranked && <p className="upload-error" role="status">평가 기준이 없거나 명시적 필수조건 미충족이 확인된 직무는 추천 순위에서 제외했어요.</p>}
      <ol className="comparison-list">{state.comparison.map(item => {
        const summary = item.result?.score.rubric ? evidenceSummary(item.result.score.rubric) : null;
        return <li key={item.role}>
        <button type="button" className={'comparison-row' + (state.analysis?.id === item.result?.id && item.result ? ' selected' : '')}
          onClick={() => item.result && state.selectComparison(item.result.id)} disabled={!item.result}
          aria-pressed={item.result ? state.analysis?.id === item.result.id : undefined}>
          <span className="comparison-rank">{item.rank == null ? '—' : item.rank + '위'}</span>
          <span><strong>{item.role}</strong>{item.error && <small>{item.error}</small>}{item.result?.score.noRequirements && <small>점수 산정 정보 부족</small>}
            {item.result?.score.rubric && <small>필수조건 {item.result.score.rubric.eligibility}</small>}
            {summary?.confirmed && <small>확인된 근거: {summary.confirmed}</small>}
            {summary?.needed && <small>보완할 근거: {summary.needed}</small>}
            {item.similarFit && <small>앞 순위와 유사 적합도</small>}</span>
          <strong>{item.result?.score.total != null && !item.result.score.noRequirements ? formatScore(item.result.score.total) + '점'
            : '점수 없음'}</strong>
        </button>
      </li>; })}</ol>
      {state.comparison.some(item => item.result?.score.rubric) && <details className="rubric-details"><summary>항목별 점수 비교</summary><div className="comparison-score-table" role="region" aria-label="직무별 항목 점수" tabIndex={0}>
        <table><caption>직무별 항목 점수 · N/A는 총점에서 제외</caption><thead><tr><th>직무</th><th>업무 /35</th><th>기술 /25</th><th>문제 해결 /15</th><th>도메인 /10</th><th>우대 /10</th><th>선호 /5</th><th>총점 /100</th></tr></thead>
          <tbody>{state.comparison.filter(item => item.result?.score.rubric).map(item => <tr key={item.role}>
            <th>{item.role}</th>{['work', 'technology', 'problem', 'domain', 'preferred', 'userPreference'].map(key => <td key={key}>{category(item, key)}</td>)}
            <td>{item.result.score.total == null ? item.result.score.rubric?.lowerTotal == null ? '—' : scoreRange(item.result.score.rubric) : formatScore(item.result.score.total)}</td>
          </tr>)}</tbody></table>
      </div></details>}
      {state.analysis && <p className="subtle">선택한 직무의 상세 분석을 아래에서 확인하세요.</p>}
    </div>
    <AnalysisResult state={state} />
  </>;
}

function AttachmentPreview({ attachment, onPick, onRemove }) {
  if (!attachment) {
    return (
      <button type="button" className="upload-zone" data-action="pick-file" onClick={onPick}>
        <span className="round-icon"><Icon name="plus" /></span>
        <strong>공고 사진이나 PDF를 올려주세요</strong>
        <span>파일을 끌어다 놓거나 클릭해서 선택하세요.<br />이 영역에 마우스를 올리고 Ctrl+V로 붙여넣을 수도 있어요.</span>
        <small>JPG · PNG · WebP · PDF / 1개, 최대 10MB</small>
      </button>
    );
  }
  return (
    <div className="attachment-card">
      <div className="attachment-heading">
        <span className="round-icon"><Icon name="file" /></span>
        <div><strong>{attachment.file.name}</strong><small>{fileSize(attachment.file.size)} · {attachment.kind === 'pdf' ? 'PDF 문서' : '공고 이미지'}</small></div>
        <button type="button" className="icon-btn" data-action="remove-file" aria-label="첨부 파일 삭제" onClick={onRemove}><Icon name="close" /></button>
      </div>
      {attachment.kind === 'image'
        ? <div className="image-preview"><img src={attachment.url} alt="첨부한 채용공고 미리보기" /></div>
        : <iframe className="pdf-preview" src={attachment.url + '#toolbar=0'} title="첨부한 채용공고 PDF 미리보기"></iframe>}
      <div className="attachment-actions">
        <button className="btn text small" type="button" data-action="pick-file" onClick={onPick}>다른 파일 선택</button>
        <a className="btn text small" href={attachment.url} target="_blank" rel="noopener">원본 열기 <Icon name="arrow" /></a>
      </div>
      {attachment.kind === 'pdf' && <p className="upload-help">PDF가 보이지 않으면 원본 열기로 확인해 주세요.</p>}
    </div>
  );
}

export function AnalyzePage({ profile, analysis: state }) {
  const fileInput = useRef(null);
  const zoneRef = useRef(null);
  const pasteRef = useRef(null);
  const [dragOver, setDragOver] = useState(false);
  // Paste goes to the focused element, not the one under the pointer, so the document listens and pasteRef decides.
  useEffect(() => {
    const onPaste = event => pasteRef.current?.(event);
    document.addEventListener('paste', onPaste);
    return () => document.removeEventListener('paste', onPaste);
  }, []);
  if (!profile) {
    return <EmptyState title="비교할 프로필이 필요해요" text="프로젝트와 사용한 기술을 먼저 등록해 주세요." href="/profile" label="프로필 작성하기" level={1} />;
  }
  const { fileMode, file, source, hasSource, currentText, canAnalyze } = state;
  const pick = () => fileInput.current?.click();
  const remove = () => {
    flushSync(state.removeAttachment);
    document.querySelector('[data-action="pick-file"]')?.focus();
  };
  const confirm = () => {
    let confirmed = false;
    flushSync(() => { confirmed = state.confirmText(); });
    if (confirmed) document.querySelector('#analyze-button')?.focus();
  };
  const drop = event => {
    if (!hasFiles(event)) return;
    event.preventDefault();
    setDragOver(false);
    void state.selectFiles([...event.dataTransfer.files]);
  };
  // Taken while the pointer is over the drop zone or focus is inside it. Only files are taken, so text still pastes as usual.
  pasteRef.current = event => {
    const zone = zoneRef.current;
    if (!zone || !(zone.matches(':hover') || zone.contains(document.activeElement))) return;
    const files = [...(event.clipboardData?.files || [])];
    if (!files.length) return;
    event.preventDefault();
    void state.selectFiles(files.map(pastedFile));
  };

  return (
    <>
      <PageHeading kicker="FIND YOUR FIT" title="이 기회, 나와 얼마나 잘 맞을까요?" description="공고를 직접 입력하거나 사진·PDF를 첨부해 나의 경험과 연결해 보세요." />
      <div className="analysis-layout">
        <section className="panel jd-panel" aria-labelledby="jd-title">
          <header className="section-heading">
            <h2 id="jd-title"><Icon name="file" /> 채용공고 입력</h2>
          </header>
          <div className="input-mode-switch" role="group" aria-label="채용공고 입력 방식">
            <button data-action="input-mode" data-mode="text" aria-pressed={!fileMode} className={!fileMode ? 'selected' : ''} onClick={() => state.setMode('text')}><Icon name="file" /> 텍스트 입력</button>
            <button data-action="input-mode" data-mode="file" aria-pressed={fileMode} className={fileMode ? 'selected' : ''} onClick={() => state.setMode('file')}><Icon name="plus" /> 사진 · PDF 첨부</button>
          </div>
          <form id="analysis-form" onSubmit={event => { event.preventDefault(); state.analyze(); }}>
            <div className="input-mode-switch" role="group" aria-label="직무 분석 방식">
              <button type="button" className={!state.comparisonMode ? 'selected' : ''} aria-pressed={!state.comparisonMode} onClick={() => state.setComparisonMode(false)}>한 직무 분석</button>
              <button type="button" className={state.comparisonMode ? 'selected' : ''} aria-pressed={state.comparisonMode} onClick={() => state.setComparisonMode(true)}>여러 직무 비교</button>
            </div>
            {state.comparisonMode ? <>
              <label className="review-label" htmlFor="comparison-roles">비교할 직무</label>
              <textarea id="comparison-roles" className="comparison-roles" required maxLength={604} rows={4}
                placeholder={'공고에 적힌 직무명을 한 줄에 하나씩 입력해 주세요\n예: IT개발\n정보보호'} value={state.comparisonRoles}
                onChange={event => state.editComparisonRoles(event.target.value)} aria-describedby="comparison-help" />
              <p className="upload-help" id="comparison-help">같은 공고에 있는 직무를 2~5개 입력해 주세요. 직무마다 별도로 분석하므로 시간이 더 걸릴 수 있어요.</p>
              {state.comparisonRoles.trim() && state.comparisonRoleError && <p className="upload-error" role="status">{state.comparisonRoleError}</p>}
            </> : <>
              <label className="review-label" htmlFor="target-role">지원 직무</label>
              <input id="target-role" name="targetRole" className="target-role-input" type="text" required maxLength={120}
                placeholder="예: 백엔드 개발자" value={state.targetRole}
                onChange={event => state.editTargetRole(event.target.value)} />
              <p className="upload-help">공고에 여러 직무가 있다면 분석할 직무 하나만 입력해 주세요.</p>
            </>}
            {fileMode ? <>
              <div id="attachment-zone" ref={zoneRef} aria-busy={String(file.busy)} className={dragOver ? 'drag-over' : ''}
                onDragOver={event => { if (!hasFiles(event)) return; event.preventDefault(); event.dataTransfer.dropEffect = 'copy'; setDragOver(true); }}
                onDragLeave={event => { if (!event.currentTarget.contains(event.relatedTarget)) setDragOver(false); }}
                onDrop={drop}>
                <input type="file" id="jd-file" className="sr-only" tabIndex={-1} accept={FILE_ACCEPT} aria-label="채용공고 파일 선택" ref={fileInput}
                  onChange={event => void state.selectFiles([...event.target.files])} />
                <AttachmentPreview attachment={file.attachment} onPick={pick} onRemove={remove} />
              </div>
              <p id="attachment-error" className="upload-error" role="alert">{file.error}</p>
            </> : null}
            {fileMode && <div className="ocr-status" role="status">
              <span><Icon name="file" /></span>
              <div>
                <strong>{source.busy ? '파일에서 텍스트를 추출하고 있어요' : source.confirmed ? '텍스트 확인 완료' : '추출 결과를 확인해 주세요'}</strong>
                <p>{source.status || 'PDF 텍스트와 이미지·스캔 PDF의 OCR 결과를 아래에서 수정한 뒤 확인해 주세요.'}</p>
              </div>
            </div>}
            <div className="review-heading">
              <label className="review-label" htmlFor="jd-text">{fileMode ? '공고 텍스트 확인·수정' : '채용공고 텍스트'}</label>
              <div className="review-actions">
                {source.confirmed && <span className="confirmed-mark" role="status"><Icon name="check" /> 확인됨</span>}
                {fileMode && <button className="btn outline small" id="confirm-text" type="button" data-action="confirm-text" onClick={confirm}
                  disabled={!hasSource || source.busy || source.text.trim().length < 20 || source.confirmed}>텍스트 확인</button>}
              </div>
            </div>
            <textarea id="jd-text" name="jd" required minLength={20} maxLength={15000} disabled={(fileMode && !file.attachment) || source.busy}
              placeholder={fileMode ? '추출 결과가 없으면 원본을 보며 직접 입력해 주세요.' : '채용공고 내용을 여기에 붙여넣어 주세요.'}
              value={currentText} onChange={event => state.editText(event.target.value)} />
            <div className="textarea-footer">
              <span>{fileMode ? '추출한 텍스트를 확인해야 분석합니다.' : '분석할 공고 내용을 입력해 주세요.'}</span>
              <span id="jd-count">{currentText.length.toLocaleString()} / 15,000</span>
            </div>
            <button className="btn full" id="analyze-button" type="submit" disabled={!canAnalyze}><Icon name="spark" /> {state.analyzing ? '분석하는 중…' : state.comparisonMode ? '직무별 적합도 비교하기' : '내 프로필과 비교하기'}</button>
          </form>
          <p className="upload-help">{fileMode
            ? '파일은 텍스트 추출을 위해 서버에 전송되며 처리 후 삭제됩니다. 브라우저 미리보기와 수정 텍스트는 새로고침하면 사라집니다.'
            : '공고에 여러 직무가 있다면 직무별 구분이 보이도록 내용을 붙여넣어 주세요.'}</p>
          <p className="upload-help">분석하면 확인한 공고 텍스트와 프로필 요약(연락처 제외)이 Anthropic Claude API로 전송되고, 결과는 내 계정에만 30일간 저장됩니다.{state.comparisonMode && ' 비교할 직무마다 별도의 분석 요청이 전송됩니다.'}</p>
          <p className="jd-profile"><Icon name="user" /><span>등록한 프로필로 비교</span><a href="#/profile" aria-label="비교할 프로필 수정">수정</a></p>
        </section>
        <section className="panel result-panel" id="analysis-output" aria-live="polite"><ComparisonResults state={state} /></section>
      </div>
    </>
  );
}
