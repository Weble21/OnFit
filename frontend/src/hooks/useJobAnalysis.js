import { useEffect, useRef, useState } from 'react';
import { analyzeJob, extractJobDraft, extractJobDraftFromUrl, retryRoleAnalysis, textHash } from '../api.js';
import { validateAttachment } from '../upload.js';

const EMPTY_ATTACHMENT = { attachment: null, text: '', error: '', busy: false, confirmed: false, confirmedText: '', draftId: null, status: '' };
const EMPTY_LINK = { url: '', text: '', error: '', busy: false, confirmed: false, confirmedText: '', draftId: null, status: '', progress: null };

/** An address typed without a scheme gets https; anything that still is not http(s) is refused. */
function normalizeUrl(value) {
  const trimmed = value.trim();
  const address = /^[a-z][a-z\d+.-]*:/i.test(trimmed) ? trimmed : 'https://' + trimmed;
  return /^https?:\/\/[^\s/?#]+/i.test(address) ? address : null;
}

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
 * Flow: posting address or file -> review and confirm the extracted text -> server structuring, score and role analysis.
 */
export function useJobAnalysis(profile, onToast) {
  const [inputMode, setInputMode] = useState('url');
  const [file, setFile] = useState(EMPTY_ATTACHMENT);
  const [link, setLink] = useState(EMPTY_LINK);
  const [analysis, setAnalysis] = useState(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [analysisError, setAnalysisError] = useState('');
  const [retrying, setRetrying] = useState(false);
  // A newer selection or removal invalidates any validation or extraction still running.
  const requestRef = useRef(0);
  const abortRef = useRef(null);
  const attachmentRef = useRef(null);
  // Same for the posting address: a newer fetch or an edited address makes an older answer irrelevant.
  const linkRequestRef = useRef(0);
  const linkAbortRef = useRef(null);
  // Same for analyses: an edit, a new file or a new request makes an older response irrelevant.
  const analysisRef = useRef(0);
  const analysisAbortRef = useRef(null);

  const fileMode = inputMode === 'file';
  // The draft under review: text read from the attached file or from the posting address.
  const source = fileMode ? file : link;
  const hasSource = fileMode ? !!file.attachment : !!link.draftId;
  const currentText = source.text;
  const canAnalyze = !analyzing && currentText.trim().length >= 20 && hasSource && !source.busy && source.confirmed;
  const update = changes => setFile(current => ({ ...current, ...changes }));
  const updateLink = changes => setLink(current => ({ ...current, ...changes }));
  const updateSource = fileMode ? update : updateLink;

  function resetAnalysis() {
    analysisRef.current++;
    analysisAbortRef.current?.abort();
    analysisAbortRef.current = null;
    setAnalyzing(false);
    setAnalysisError('');
    setAnalysis(null);
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

  function editUrl(value) {
    linkRequestRef.current++;
    linkAbortRef.current?.abort();
    linkAbortRef.current = null;
    setLink({ ...EMPTY_LINK, url: value });
    resetAnalysis();
  }

  async function fetchUrl() {
    const address = normalizeUrl(link.url);
    if (!address) { updateLink({ error: 'http 또는 https로 시작하는 공고 주소를 입력해 주세요.' }); return; }
    const request = ++linkRequestRef.current;
    linkAbortRef.current?.abort();
    const controller = new AbortController();
    linkAbortRef.current = controller;
    setLink({ ...EMPTY_LINK, url: address, busy: true });
    resetAnalysis();
    try {
      const result = await extractJobDraftFromUrl(address, controller.signal,
        progress => { if (request === linkRequestRef.current) updateLink({ progress }); });
      if (request !== linkRequestRef.current) return;
      updateLink({ text: result.text || '', draftId: result.draftId, status: draftStatus(result) });
    } catch (error) {
      if (request !== linkRequestRef.current || error.name === 'AbortError') return;
      updateLink({ error: error.message });
    } finally {
      if (request === linkRequestRef.current) { updateLink({ busy: false, progress: null }); linkAbortRef.current = null; }
    }
  }

  async function analyze() {
    if (!canAnalyze) {
      if (!analyzing) onToast((fileMode ? '파일을 올리고' : '공고 주소에서 내용을 가져오고') + ' 추출한 텍스트를 확인해 주세요.');
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
        ? { inputType: 'URL', text: link.text, draftId: link.draftId, confirmedTextHash: await textHash(link.confirmedText) }
        : file.draftId
          ? { inputType: 'FILE', text: file.text, draftId: file.draftId, confirmedTextHash: await textHash(file.confirmedText) }
          : { inputType: 'TEXT', text: file.text };
      const result = await analyzeJob(body, controller.signal);
      if (request !== analysisRef.current) return;
      setAnalysis(result);
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
      if (request === analysisRef.current) setAnalysis(result);
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
    inputMode, fileMode, file, link, source, hasSource, analysis, analyzing, analysisError, retrying, currentText, canAnalyze,
    setMode(mode) { setInputMode(mode); resetAnalysis(); },
    editUrl,
    fetchUrl,
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
