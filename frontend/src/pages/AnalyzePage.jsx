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
      <p>AI 구조화와 직무 분석까지 1~2분 정도 걸릴 수 있어요.<br />텍스트를 수정하면 진행 중인 분석은 취소됩니다.</p>
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
    return <>{title}<div className="analysis-placeholder">
      <div className="analysis-illustration" aria-hidden="true"><Icon name="file" /><span><Icon name="spark" /></span></div>
      <h3>이 공고, 나와 얼마나 잘 맞을까요?</h3>
      <p>공고를 붙여넣으면 AI가 요구사항을 정리하고<br />등록한 프로필과 비교해 맞춤 점수와 직무 분석을 보여드려요.</p>
      <ol className="placeholder-steps" aria-label="분석 순서"><li>01 공고 입력</li><li><Icon name="chevron" />02 구조화·점수 계산</li><li><Icon name="chevron" />03 직무 분석</li></ol>
    </div></>;
  }
  const { score, posting } = analysis;
  const semanticOk = score.semanticStatus === 'OK';
  return <>{title}<article className="analysis-result">
    <div className="result-heading"><span className="eyebrow">YOUR FIT REPORT</span><Pill>AI 구조화 · 규칙 기반 점수</Pill></div>
    {analysis.stale && <p className="upload-error" role="alert">이 결과 이후 프로필이 바뀌었어요. 다시 비교하면 새 프로필로 계산합니다.</p>}
    <div className="result-score">
      <div className="score-circle" style={{ '--score': score.total / score.maxTotal * 100 }}><div><strong>{formatScore(score.total)}<small>점</small></strong><span>맞춤 점수</span></div></div>
      <div>
        <h3>{score.noRequirements ? '공고에서 요구 기술을 찾지 못했어요.' : '나의 프로필과 비교했어요.'}</h3>
        <p>{semanticOk ? '100점 만점' : '현재 최대 ' + formatScore(score.maxTotal) + '점'} · 필수 기술 {score.matchedRequired.length}/{score.matchedRequired.length + score.missing.length}개 일치</p>
      </div>
    </div>
    <Metric label="필수 기술 (35%)" value={score.noRequirements ? null : score.required} note="요구 기술 없음" />
    <Metric label="우대 기술 (20%)" value={posting.preferredSkills.length ? score.preferred : null} note="우대 기술 없음" />
    <Metric label="의미 유사도 (20%)" value={semanticOk ? score.semantic : null} note={score.semanticStatus === 'FAILED' ? '계산 실패' : '사용 안 함'} />
    <Metric label="경력·프로젝트 연관 (15%)" value={score.experience} />
    <Metric label="희망 직무·지역 (10%)" value={score.preference} />
    {!semanticOk && <p className="subtle score-note">{SEMANTIC_NOTE[score.semanticStatus]}</p>}
    <section className="result-list"><h3><Icon name="check" /> 일치하는 기술</h3><SkillResult skills={[...score.matchedRequired, ...score.matchedPreferred]} className="matched" emptyText="등록된 기술 중 일치하는 항목이 없어요." /></section>
    <section className="result-list"><h3><Icon name="plus" /> 프로필에서 확인되지 않은 필수 기술</h3><SkillResult skills={score.missing} className="missing" emptyText={score.noRequirements ? '공고에 필수 기술이 명시되지 않았어요.' : '필수 기술은 모두 프로필에 있어요.'} /></section>
    <section className="result-list" aria-labelledby="posting-title">
      <h3 id="posting-title"><Icon name="file" /> AI가 정리한 공고</h3>
      <dl className="posting-facts">{POSTING_FIELDS.map(([key, label]) => <div key={key}><dt>{label}</dt><dd>{posting[key].value || '공고에 없음'}</dd></div>)}</dl>
      {posting.ambiguities.length > 0 && <ul className="role-items">{posting.ambiguities.map((item, i) => <li className="role-item" key={i}><p>모호한 조건: {item.text}</p><Evidence item={{ evidence: [item.evidence] }} labels={new Map()} /></li>)}</ul>}
    </section>
    <RoleAnalysis analysis={analysis} retrying={state.retrying} onRetry={state.retryRole} />
    <p className="analysis-caveat" role="note">점수는 AI가 아닌 규칙 기반 계산기가 정하고, AI 직무 분석은 점수를 바꾸지 않습니다. 모든 설명은 공고 원문이나 프로필 항목에서 인용 근거를 찾은 내용만 표시합니다. 프로필에 없는 기술은 실제 역량 부족을 의미하지 않습니다.</p>
    <details className="analysis-versions"><summary>계산 정보</summary>
      <p>구조화 {analysis.versions.extractionModel} · {analysis.versions.extractionPrompt} / 계산기 {analysis.versions.calculator} / 의미 유사도 {analysis.versions.semanticModel}{analysis.versions.roleModel && ' / 직무 분석 ' + analysis.versions.roleModel + ' · ' + analysis.versions.rolePrompt}</p>
    </details>
  </article></>;
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
  const { fileMode, file, currentText, canAnalyze } = state;
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
      <PageHeading kicker="FIND YOUR FIT" title="이 기회, 나와 얼마나 잘 맞을까요?" description="공고를 붙여넣거나 사진·PDF를 첨부해 나의 경험과 연결해 보세요." />
      <div className="analysis-layout">
        <section className="panel jd-panel" aria-labelledby="jd-title">
          <header className="section-heading">
            <h2 id="jd-title"><Icon name="file" /> 채용공고 입력</h2>
            {!fileMode && <button className="btn text small" data-action="sample-jd" onClick={state.loadSample}>예시 불러오기</button>}
          </header>
          <div className="input-mode-switch" role="group" aria-label="채용공고 입력 방식">
            <button data-action="input-mode" data-mode="text" aria-pressed={!fileMode} className={!fileMode ? 'selected' : ''} onClick={() => state.setMode('text')}><Icon name="file" /> 텍스트 입력</button>
            <button data-action="input-mode" data-mode="file" aria-pressed={fileMode} className={fileMode ? 'selected' : ''} onClick={() => state.setMode('file')}><Icon name="plus" /> 사진 · PDF 첨부</button>
          </div>
          <form id="analysis-form" onSubmit={event => { event.preventDefault(); state.analyze(); }}>
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
              <div className="ocr-status" role="status">
                <span><Icon name="file" /></span>
                <div>
                  <strong>{file.busy ? '파일에서 텍스트를 추출하고 있어요' : file.confirmed ? '텍스트 확인 완료' : '추출 결과를 확인해 주세요'}</strong>
                  <p>{file.status || 'PDF 텍스트와 이미지·스캔 PDF의 OCR 결과를 아래에서 수정한 뒤 확인해 주세요.'}</p>
                </div>
              </div>
            </> : <p className="subtle input-help">담당 업무, 자격요건, 우대사항을 함께 넣어주세요.</p>}
            {fileMode ? (
              <div className="review-heading">
                <label className="review-label" htmlFor="jd-text">공고 텍스트 확인·수정</label>
                <div className="review-actions">
                  {file.confirmed && <span className="confirmed-mark" role="status"><Icon name="check" /> 확인됨</span>}
                  <button className="btn outline small" id="confirm-text" type="button" data-action="confirm-text" onClick={confirm}
                    disabled={!file.attachment || file.busy || file.text.trim().length < 20 || file.confirmed}>텍스트 확인</button>
                </div>
              </div>
            ) : <label className="sr-only" htmlFor="jd-text">채용공고 또는 Job Description</label>}
            <textarea id="jd-text" name="jd" required minLength={20} maxLength={15000} disabled={fileMode && (!file.attachment || file.busy)}
              placeholder={fileMode ? '추출 결과가 없으면 원본을 보며 직접 입력해 주세요.' : '분석할 공고를 붙여넣으세요.\n\n자격요건\n· Java, Spring Boot 기반의 개발 경험\n\n우대사항\n· Docker 기반 배포 경험'}
              value={currentText} onChange={event => state.editText(event.target.value)} />
            <div className="textarea-footer">
              <span>{fileMode ? '입력·수정한 텍스트를 확인해야 분석합니다.' : '텍스트로 입력 · 최소 20자'}</span>
              <span id="jd-count">{currentText.length.toLocaleString()} / 15,000</span>
            </div>
            <button className="btn full" id="analyze-button" type="submit" disabled={!canAnalyze}><Icon name="spark" /> {state.analyzing ? '분석하는 중…' : '내 프로필과 비교하기'}</button>
          </form>
          {fileMode && <p className="upload-help">파일은 텍스트 추출을 위해 서버에 전송되며 처리 후 삭제됩니다. 브라우저 미리보기와 수정 텍스트는 새로고침하면 사라집니다.</p>}
          <p className="upload-help">분석하면 확인한 공고 텍스트와 프로필 요약(연락처 제외)이 Anthropic Claude API로 전송되고, 결과는 내 계정에만 30일간 저장됩니다.</p>
          <p className="jd-profile"><Icon name="user" /><span>{profile.roles.join(', ')} · {profile.career} 프로필로 비교</span><a href="#/profile" aria-label="비교할 프로필 수정">수정</a></p>
        </section>
        <section className="panel result-panel" id="analysis-output" aria-live="polite"><AnalysisResult state={state} /></section>
      </div>
    </>
  );
}
