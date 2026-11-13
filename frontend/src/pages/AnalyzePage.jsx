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

function Metric({ label, value, note }) {
  return (
    <div className="metric">
      <div><span>{label}</span><strong>{value === null ? note : formatScore(value) + '점'}</strong></div>
      <div className="meter" aria-hidden="true"><span style={{ width: (value ?? 0) + '%' }}></span></div>
    </div>
  );
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
  </>;
}

function AnalysisResult({ state }) {
  const { analysis, analyzing, analysisError } = state;
  const title = <h2 className="sr-only">적합도 분석 결과</h2>;
  if (analyzing) {
    return <>{title}<div className="analysis-placeholder" aria-busy="true">
      <span className="round-icon" aria-hidden="true"><Icon name="spark" /></span>
      <h3>공고를 구조화하고 프로필과 비교하고 있어요.</h3>
      <p>{state.comparisonMode && state.comparisonProgress ? `${state.comparisonProgress.completed}/${state.comparisonProgress.total}개 직무 분석 완료. ` : ''}AI 구조화와 직무 분석까지 시간이 걸릴 수 있어요.<br />텍스트를 수정하면 진행 중인 분석은 취소됩니다.</p>
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
  const semanticOk = score.semanticStatus === 'OK';
  const legacyScoring = !['rules-v1/analysis-web-duty/adapter-v5', 'rules-v1/analysis-certificates/adapter-v6', 'rules-v1/preference-items/adapter-v7'].includes(analysis.versions.calculator);
  const primarySkills = posting.requiredSkills.length ? posting.requiredSkills : (posting.dutySkills || []);
  const skillBasis = posting.requiredSkills.length ? '필수 기술' : '업무 기술';
  const insufficient = score.noRequirements;
  const relatedRequirements = new Set((score.relatedMatches || []).map(item => item.requirement));
  return <>{title}<article className="analysis-result">
    <div className="result-heading"><span className="eyebrow">YOUR FIT REPORT · {analysis.targetRole}</span><Pill>AI 구조화 · 규칙 기반 점수</Pill></div>
    {analysis.stale && <p className="upload-error" role="alert">이 결과 이후 프로필이 바뀌었어요. 다시 비교하면 새 프로필로 계산합니다.</p>}
    <div className="result-score">
      <div className="score-circle" style={{ '--score': insufficient ? 0 : score.total / score.maxTotal * 100 }}><div><strong>{insufficient ? '—' : formatScore(score.total)}{!insufficient && <small>점</small>}</strong><span>{insufficient ? '정보 부족' : '맞춤 점수'}</span></div></div>
      <div>
        <h3>{insufficient ? '총점을 산정하기에 정보가 부족해요.' : '나의 프로필과 비교했어요.'}</h3>
        <p>{insufficient ? '비교할 기술을 확인하지 못해 총점 표시를 보류합니다. 낮은 적합도를 뜻하지 않습니다.' : <>{score.maxTotal === 100 ? '100점 만점' : '현재 최대 ' + formatScore(score.maxTotal) + '점'} · {skillBasis} {score.matchedRequired.length}/{primarySkills.length}개 일치·관련</>}</p>
      </div>
    </div>
    <Metric label={skillBasis + " (" + (score.requiredWeight ?? 40) + "%)"} value={primarySkills.length ? score.required : null} note="비교 기준 없음" />
    {(score.dutyWeight ?? 0) > 0 && <Metric label={"업무 기술 (" + score.dutyWeight + "%)"} value={score.duty} note="비교 기준 없음" />}
    <Metric label="우대사항 (20%)" value={score.preferredCriteria?.length || posting.preferredSkills.length || score.preferredCertificates?.length ? score.preferred : null} note="비교할 우대사항 없음" />
    {legacyScoring && <Metric label="의미 유사도 (20%)" value={semanticOk ? score.semantic : null} note={score.semanticStatus === 'FAILED' ? '계산 실패' : '사용 안 함'} />}
    <Metric label={'경력·프로젝트 연관 (' + (legacyScoring ? 20 : 30) + '%)'} value={insufficient ? null : score.experience} note="평가 정보 부족" />
    {!posting.requiredSkills.length && primarySkills.length > 0 && <p className="subtle score-note">필수 기술이 별도로 명시되지 않아 담당 업무에 나온 기술을 비교했습니다. 필수 자격요건을 뜻하지 않습니다.</p>}
    {legacyScoring && <p className="subtle score-note">이전 점수 기준으로 계산된 결과입니다. 다시 비교하면 의미 유사도를 제외한 새 기준이 적용됩니다.</p>}
    {legacyScoring && !semanticOk && <p className="subtle score-note">{SEMANTIC_NOTE[score.semanticStatus]}</p>}
    {score.requiredCertificates?.length > 0 && <section className="result-list">
      <h3><Icon name="file" /> 필수 자격증</h3>
      {score.requiredCertificates.map((item, i) => <div className="role-item" key={i}>
        <p>{item.condition} · {item.matched ? '프로필에서 확인: ' + item.matchedCertificates.join(', ') : '프로필에서 확인되지 않음 · 총점 0점'}</p>
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
    <p className="analysis-caveat" role="note">점수는 AI가 아닌 규칙 기반 계산기가 정하고, AI 직무 분석은 점수를 바꾸지 않습니다. 모든 설명은 공고 원문이나 프로필 항목에서 인용 근거를 찾은 내용만 표시합니다. 프로필에 없는 기술은 실제 역량 부족을 의미하지 않습니다.</p>
    <details className="analysis-versions"><summary>계산 정보</summary>
      <p>구조화 {analysis.versions.extractionModel} · {analysis.versions.extractionPrompt} / 계산기 {analysis.versions.calculator}{legacyScoring && ' / 의미 유사도 ' + analysis.versions.semanticModel}{analysis.versions.roleModel && ' / 직무 분석 ' + analysis.versions.roleModel + ' · ' + analysis.versions.rolePrompt}</p>
    </details>
  </article></>;
}

function ComparisonResults({ state }) {
  if (!state.comparison?.length || state.analyzing) return <AnalysisResult state={state} />;
  const hasFailures = state.comparison.some(item => item.error || item.rank == null);
  return <>
    <div className="comparison-results">
      <span className="eyebrow">ROLE COMPARISON</span>
      <h2>직무별 적합도 비교</h2>
      <p className="subtle">입력한 직무를 같은 프로필로 각각 분석한 결과입니다. 공고별 요구 정보가 다를 수 있으니 점수와 근거를 함께 확인해 주세요.</p>
      {hasFailures && <p className="upload-error" role="status">분석에 실패했거나 점수 산정 정보가 부족한 직무는 순위에서 제외했어요.</p>}
      <ol className="comparison-list">{state.comparison.map(item => <li key={item.role}>
        <button type="button" className={'comparison-row' + (state.analysis?.id === item.result?.id && item.result ? ' selected' : '')}
          onClick={() => item.result && state.selectComparison(item.result.id)} disabled={!item.result}
          aria-pressed={item.result ? state.analysis?.id === item.result.id : undefined}>
          <span className="comparison-rank">{item.rank == null ? '—' : item.rank + '위'}</span>
          <span><strong>{item.role}</strong>{item.error && <small>{item.error}</small>}{item.result?.score.noRequirements && <small>점수 산정 정보 부족</small>}</span>
          <strong>{item.result?.score.total != null && !item.result.score.noRequirements ? formatScore(item.result.score.total) + '점' : '점수 없음'}</strong>
        </button>
      </li>)}</ol>
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
