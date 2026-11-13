import { useEffect, useRef, useState } from 'react';
import { analyzeJob, extractJobDraft, retryRoleAnalysis, textHash } from '../api.js';
import { validateAttachment } from '../upload.js';
import { compareRoles, parseComparisonRoles, rankComparison } from '../roleComparison.js';

const EMPTY_ATTACHMENT = { attachment: null, text: '', error: '', busy: false, confirmed: false, confirmedText: '', draftId: null, status: '' };
function draftStatus(result) {
  const how = {
    URL_HTML: '웹페이지 텍스트 추출 완료',
    URL_OCR: '웹페이지 이미지 ' + result.pages + '장 OCR 완료',
    URL_PDF: '연결된 PDF 추출 완료 · ' + result.pages + '쪽',
    PDF_TEXT: 'PDF 텍스트 추출 완료 · ' + result.pages + '쪽',
  }[result.method] || 'OCR 처리 완료 · ' + result.pages + '쪽';
  return how + (result.truncated ? ' · 15,000자까지만 표시' : '') + '. 원본과 비교하고 수정한 뒤 확인해 주세요.';
}

/**
 * State of the JD analysis page. It lives above the page so typed text and the attached file
 * survive moving to another screen and back, as before.
 * Flow: typed posting text or a reviewed file -> server structuring, score and role analysis.
 */
export function useJobAnalysis(profile, onToast) {
  const [inputMode, setInputMode] = useState('text');
  const [manual, setManual] = useState({ text: '', busy: false, confirmed: false, status: '' });
  const [file, setFile] = useState(EMPTY_ATTACHMENT);
  const [analysis, setAnalysis] = useState(null);
  const [targetRole, setTargetRole] = useState('');
  const [comparisonMode, setComparisonMode] = useState(false);
  const [comparisonRoles, setComparisonRoles] = useState('');
  const [comparison, setComparison] = useState(null);
  const [comparisonProgress, setComparisonProgress] = useState(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [analysisError, setAnalysisError] = useState('');
  const [retrying, setRetrying] = useState(false);
  // A newer selection or removal invalidates any validation or extraction still running.
  const requestRef = useRef(0);
  const abortRef = useRef(null);
  const attachmentRef = useRef(null);
  // Same for analyses: an edit, a new file or a new request makes an older response irrelevant.
  const analysisRef = useRef(0);
  const analysisAbortRef = useRef(null);

  const fileMode = inputMode === 'file';
  // The draft under review: text typed directly or read from the attached file.
  const source = fileMode ? file : manual;
  const hasSource = fileMode ? !!file.attachment : true;
  const currentText = source.text;
  const roleInput = parseComparisonRoles(comparisonRoles);
  const canAnalyze = !analyzing && (comparisonMode ? !roleInput.error : targetRole.trim().length > 0) && currentText.trim().length >= 20 && hasSource && !source.busy && (!fileMode || source.confirmed);
  const update = changes => setFile(current => ({ ...current, ...changes }));
  const updateSource = fileMode ? update : changes => setManual(current => ({ ...current, ...changes }));

  function resetAnalysis() {
    analysisRef.current++;
    analysisAbortRef.current?.abort();
    analysisAbortRef.current = null;
    setAnalyzing(false);
    setAnalysisError('');
    setAnalysis(null);
    setComparison(null);
    setComparisonProgress(null);
  }

  function removeAttachment() {
    requestRef.current++;
    abortRef.current?.abort();
    abortRef.current = null;
    if (attachmentRef.current) URL.revokeObjectURL(attachmentRef.current.url);
    attachmentRef.current = null;
    setFile(EMPTY_ATTACHMENT);
    resetAnalysis();
  }

  async function selectFiles(files) {
    if (!files.length) return;
    const request = ++requestRef.current;
    if (files.length !== 1) { update({ busy: false, error: '공고 파일은 한 번에 1개씩 선택해 주세요.' }); return; }
    update({ busy: true, error: '' });
    try {
      const selected = files[0];
      const info = await validateAttachment(selected);
      if (info.kind === 'image') (await createImageBitmap(selected)).close();
      if (request !== requestRef.current) return;
      const url = URL.createObjectURL(selected.slice(0, selected.size, info.mime));
      abortRef.current?.abort();
      abortRef.current = new AbortController();
      if (attachmentRef.current) URL.revokeObjectURL(attachmentRef.current.url);
      attachmentRef.current = { file: selected, url, ...info };
      update({ attachment: attachmentRef.current, text: '', confirmed: false, confirmedText: '', draftId: null, status: '' });
      resetAnalysis();
      const result = await extractJobDraft(selected, abortRef.current.signal);
      if (request !== requestRef.current) return;
      update({ text: result.text || '', draftId: result.draftId, status: draftStatus(result) });
    } catch (error) {
      if (request !== requestRef.current || error.name === 'AbortError') return;
      update({
        error: error instanceof DOMException ? '이미지를 읽을 수 없어요. 다른 원본 파일을 선택해 주세요.' : error.message,
        status: attachmentRef.current ? '추출에 실패했습니다. 원본을 보며 텍스트를 직접 입력할 수 있습니다.' : '',
      });
    } finally {
      if (request === requestRef.current) { update({ busy: false }); abortRef.current = null; }
    }
  }

  async function analyze() {
    if (!canAnalyze) {
      if (!analyzing) onToast(fileMode ? '파일을 올리고 추출한 텍스트를 확인해 주세요.' : '지원 직무와 공고 텍스트를 입력해 주세요.');
      return;
    }
    resetAnalysis();
    const request = analysisRef.current;
    const controller = new AbortController();
    analysisAbortRef.current = controller;
    setAnalyzing(true);
    try {
      // Text typed by hand next to a file whose extraction failed has no draft, so it is analyzed as typed text.
      const body = !fileMode
        ? { inputType: 'TEXT', text: manual.text }
        : file.draftId
          ? { inputType: 'FILE', text: file.text, draftId: file.draftId, confirmedTextHash: await textHash(file.confirmedText) }
          : { inputType: 'TEXT', text: file.text };
      if (comparisonMode) {
        const entries = await compareRoles(roleInput.roles, body, analyzeJob, controller.signal, (completed, total) => {
          if (request === analysisRef.current) setComparisonProgress({ completed: completed.length, total });
        });
        if (request !== analysisRef.current) return;
        const ranked = rankComparison(entries);
        setComparison(ranked);
        setAnalysis(ranked.find(item => item.result)?.result || null);
      } else {
        const result = await analyzeJob({ ...body, targetRole: targetRole.trim() }, controller.signal);
        if (request !== analysisRef.current) return;
        setAnalysis(result);
      }
    } catch (error) {
      if (request !== analysisRef.current || error.name === 'AbortError') return;
      setAnalysisError(error.message);
    } finally {
      if (request === analysisRef.current) { setAnalyzing(false); analysisAbortRef.current = null; }
    }
  }

  async function retryRole() {
    if (!analysis || retrying) return;
    const request = analysisRef.current;
    setRetrying(true);
    try {
      const result = await retryRoleAnalysis(analysis.id);
      if (request === analysisRef.current) {
        setAnalysis(result);
        setComparison(current => current?.map(item => item.result?.id === result.id ? { ...item, result } : item) || null);
      }
    } catch (error) {
      if (request === analysisRef.current) onToast(error.message);
    } finally {
      setRetrying(false);
    }
  }

  useEffect(() => {
    const onPageHide = event => { if (!event.persisted) removeAttachment(); };
    window.addEventListener('pagehide', onPageHide);
    return () => window.removeEventListener('pagehide', onPageHide);
  }, []);

  return {
    inputMode, fileMode, file, source, hasSource, analysis, analyzing, analysisError, retrying, currentText, canAnalyze, targetRole,
    comparisonMode, comparisonRoles, comparison, comparisonProgress, comparisonRoleError: roleInput.error,
    setComparisonMode(value) { setComparisonMode(value); resetAnalysis(); },
    editComparisonRoles(value) { setComparisonRoles(value); resetAnalysis(); },
    selectComparison(id) { const found = comparison?.find(item => item.result?.id === id); if (found) setAnalysis(found.result); },
    editTargetRole(value) { setTargetRole(value); resetAnalysis(); },
    setMode(mode) { setInputMode(mode); resetAnalysis(); },
    editText(value) {
      updateSource({ text: value, confirmed: false, status: '수정한 텍스트를 다시 확인해 주세요.' });
      resetAnalysis();
    },
    confirmText() {
      if (!hasSource || source.busy || source.text.trim().length < 20) return false;
      updateSource({ confirmed: true, confirmedText: source.text, status: '확인한 텍스트만 분석합니다. 수정하면 다시 확인해 주세요.' });
      return true;
    },
    analyze,
    retryRole,
    /** A saved profile makes the previous comparison stale. */
    clearResult: resetAnalysis,
    selectFiles,
    removeAttachment,
  };
}
