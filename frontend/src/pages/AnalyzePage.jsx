import { useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { FILE_ACCEPT, fileSize } from '../upload.js';
import { EmptyState, Icon, PageHeading, Pill, SkillResult } from '../components/ui.jsx';

const hasFiles = event => event.dataTransfer?.types.includes('Files');

function Metric({ label, result }) {
  return (
    <div className="metric">
      <div><span>{label}</span><strong>{result.score === null ? '확인 필요' : result.score + '%'}</strong></div>
      <div className="meter" aria-hidden="true"><span style={{ width: (result.score ?? 0) + '%' }}></span></div>
    </div>
  );
}

function AnalysisResult({ analysis }) {
  const title = <h2 className="sr-only">적합도 분석 결과</h2>;
  if (!analysis) {
    return <>{title}<div className="analysis-placeholder">
      <div className="analysis-illustration" aria-hidden="true"><Icon name="file" /><span><Icon name="spark" /></span></div>
      <h3>이 공고, 나와 얼마나 잘 맞을까요?</h3>
      <p>공고를 붙여넣으면 등록한 기술과 비교해<br />잘 맞는 부분과 준비할 부분을 보여드려요.</p>
      <ol className="placeholder-steps" aria-label="분석 순서"><li>01 공고 입력</li><li><Icon name="chevron" />02 프로필 비교</li><li><Icon name="chevron" />03 결과 확인</li></ol>
    </div></>;
  }
  if (!analysis.all.length) {
    return <>{title}<div className="analysis-placeholder">
      <span className="round-icon" aria-hidden="true"><Icon name="search" /></span>
      <h3>비교할 기술을 찾지 못했어요.</h3>
      <p>이 데모는 Java, React, Python 등 정해진 기술명을 인식해요.<br />기술 요구사항이 포함된 공고를 입력해 주세요.</p>
    </div></>;
  }
  return <>{title}<article className="analysis-result">
    <div className="result-heading"><span className="eyebrow">YOUR FIT REPORT</span><Pill>키워드 비교 데모</Pill></div>
    <div className="result-score">
      <div className="score-circle" style={{ '--score': analysis.overall.score }}><div><strong>{analysis.overall.score}<small>%</small></strong><span>기술 일치율</span></div></div>
      <div><h3>나의 경험과<br />연결되는 지점을 찾았어요.</h3><p>인식한 기술 {analysis.all.length}개 중 {analysis.overall.matched.length}개 일치</p></div>
    </div>
    <Metric label="필수 기술 일치율" result={analysis.required} />
    <Metric label="우대 기술 일치율" result={analysis.preferred} />
    <section className="result-list"><h3><Icon name="check" /> 연결되는 기술</h3><SkillResult skills={analysis.overall.matched} className="matched" emptyText="등록된 기술 중 일치하는 항목이 없어요." /></section>
    <section className="result-list"><h3><Icon name="plus" /> 프로필에서 확인되지 않은 기술</h3><SkillResult skills={analysis.overall.missing} className="missing" emptyText="인식된 기술은 모두 프로필에 있어요." /></section>
    <p className="analysis-caveat" role="note">프로젝트의 깊이, 직무·부서 적합도, 경력 기간은 아직 평가하지 않습니다. 필수·우대 제목이 없는 항목은 전체 기술 일치율에만 반영됩니다. 미등록 기술은 실제 역량 부족을 의미하지 않습니다.</p>
  </article></>;
}

function AttachmentPreview({ attachment, onPick, onRemove }) {
  if (!attachment) {
    return (
      <button type="button" className="upload-zone" data-action="pick-file" onClick={onPick}>
        <span className="round-icon"><Icon name="plus" /></span>
        <strong>공고 사진이나 PDF를 올려주세요</strong>
        <span>파일을 끌어다 놓거나 클릭해서 선택하세요.</span>
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
  const [dragOver, setDragOver] = useState(false);
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
              <div id="attachment-zone" aria-busy={String(file.busy)} className={dragOver ? 'drag-over' : ''}
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
            <label className={fileMode ? 'review-label' : 'sr-only'} htmlFor="jd-text">{fileMode ? '공고 텍스트 확인·수정' : '채용공고 또는 Job Description'}</label>
            <textarea id="jd-text" name="jd" required minLength={20} maxLength={15000} disabled={fileMode && (!file.attachment || file.busy)}
              placeholder={fileMode ? '추출 결과가 없으면 원본을 보며 직접 입력해 주세요.' : '분석할 공고를 붙여넣으세요.\n\n자격요건\n· Java, Spring Boot 기반의 개발 경험\n\n우대사항\n· Docker 기반 배포 경험'}
              value={currentText} onChange={event => state.editText(event.target.value)} />
            <div className="textarea-footer">
              <span>{fileMode ? '입력·수정한 텍스트를 확인해야 분석합니다.' : '텍스트로 입력 · 최소 20자'}</span>
              <span id="jd-count">{currentText.length.toLocaleString()} / 15,000</span>
            </div>
            {fileMode && <button className="btn text small" id="confirm-text" type="button" data-action="confirm-text" onClick={confirm}
              disabled={!file.attachment || file.busy || file.text.trim().length < 20 || file.confirmed}>텍스트 확인</button>}
            <button className="btn full" id="analyze-button" type="submit" disabled={!canAnalyze}><Icon name="spark" /> 내 프로필과 비교하기</button>
          </form>
          {fileMode && <p className="upload-help">파일은 텍스트 추출을 위해 서버에 전송되며 처리 후 삭제됩니다. 브라우저 미리보기와 수정 텍스트는 새로고침하면 사라집니다.</p>}
          <p className="jd-profile"><Icon name="user" /><span>{profile.role} · {profile.career} 프로필로 비교</span><a href="#/profile" aria-label="비교할 프로필 수정">수정</a></p>
        </section>
        <section className="panel result-panel" id="analysis-output" aria-live="polite"><AnalysisResult analysis={state.analysis} /></section>
      </div>
    </>
  );
}
