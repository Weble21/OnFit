import { useEffect, useRef, useState } from 'react';
import { analyzeText, sampleJD } from '../data.js';
import { extractJobText } from '../api.js';
import { validateAttachment } from '../upload.js';

const EMPTY_ATTACHMENT = { attachment: null, text: '', error: '', busy: false, confirmed: false, status: '' };

/**
 * State of the JD analysis page. It lives above the page so typed text and the attached file
 * survive moving to another screen and back, as before.
 */
export function useJobAnalysis(profile, onToast) {
  const [inputMode, setInputMode] = useState('text');
  const [jdText, setJdText] = useState('');
  const [file, setFile] = useState(EMPTY_ATTACHMENT);
  const [analysis, setAnalysis] = useState(null);
  // A newer selection or removal invalidates any validation or extraction still running.
  const requestRef = useRef(0);
  const abortRef = useRef(null);
  const attachmentRef = useRef(null);

  const fileMode = inputMode === 'file';
  const currentText = fileMode ? file.text : jdText;
  const canAnalyze = currentText.trim().length >= 20 && (!fileMode || (!!file.attachment && !file.busy && file.confirmed));
  const update = changes => setFile(current => ({ ...current, ...changes }));

  function removeAttachment() {
    requestRef.current++;
    abortRef.current?.abort();
    abortRef.current = null;
    if (attachmentRef.current) URL.revokeObjectURL(attachmentRef.current.url);
    attachmentRef.current = null;
    setFile(EMPTY_ATTACHMENT);
    setAnalysis(null);
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
      update({ attachment: attachmentRef.current, text: '', confirmed: false, status: '' });
      setAnalysis(null);
      const result = await extractJobText(selected, abortRef.current.signal);
      if (request !== requestRef.current) return;
      update({
        text: result.text || '',
        status: (result.method === 'PDF_TEXT' ? 'PDF 텍스트 추출' : 'OCR 처리') + ' 완료 · ' + result.pages + '쪽'
          + (result.truncated ? ' · 15,000자까지만 표시' : '') + '. 원본과 비교하고 수정한 뒤 확인해 주세요.',
      });
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

  useEffect(() => {
    const onPageHide = event => { if (!event.persisted) removeAttachment(); };
    window.addEventListener('pagehide', onPageHide);
    return () => window.removeEventListener('pagehide', onPageHide);
  }, []);

  return {
    inputMode, fileMode, jdText, file, analysis, currentText, canAnalyze,
    setMode(mode) { setInputMode(mode); setAnalysis(null); },
    loadSample() { setInputMode('text'); setJdText(sampleJD); setAnalysis(null); },
    editText(value) {
      if (fileMode) update({ text: value, confirmed: false, status: '수정한 텍스트를 다시 확인해 주세요.' });
      else setJdText(value);
      setAnalysis(null);
    },
    confirmText() {
      if (!file.attachment || file.busy || file.text.trim().length < 20) return false;
      update({ confirmed: true, status: '확인한 텍스트만 분석합니다. 수정하면 다시 확인해 주세요.' });
      return true;
    },
    analyze() {
      if (!canAnalyze) { onToast('공고 텍스트를 20자 이상 입력하고, 파일 모드에서는 수정한 텍스트를 확인해 주세요.'); return; }
      setAnalysis(analyzeText(currentText, profile));
    },
    /** A saved profile makes the previous comparison stale. */
    clearResult() { setAnalysis(null); },
    selectFiles,
    removeAttachment,
  };
}
