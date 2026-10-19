import { useCallback, useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

export function useToast() {
  const [toast, setToast] = useState({ message: '', visible: false });
  const timer = useRef(null);
  const showToast = useCallback(message => {
    setToast({ message, visible: true });
    clearTimeout(timer.current);
    timer.current = setTimeout(() => setToast(current => ({ ...current, visible: false })), 3500);
  }, []);
  useEffect(() => () => clearTimeout(timer.current), []);
  return [toast, showToast];
}

/** Lives outside #app so it is still announced while a modal makes #app inert. */
export function Toast({ message, visible }) {
  return createPortal(
    <div id="toast" className={visible ? 'visible' : ''} role="status" aria-live="polite">{message}</div>,
    document.body,
  );
}
