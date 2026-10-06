import { useEffect, useRef } from 'react';
import { createPortal } from 'react-dom';
import { Icon } from './ui.jsx';

const FOCUSABLE = 'button:not([disabled]),a[href],input,textarea,select';

/**
 * Dialog rendered outside #app so the page behind it can be made inert.
 * Focus moves to the close button on open and returns to the trigger on close.
 */
export function Modal({ label, onClose, children }) {
  const dialog = useRef(null);
  const close = useRef(onClose);
  close.current = onClose;

  useEffect(() => {
    const trigger = document.activeElement;
    const app = document.getElementById('app');
    app.inert = true;
    document.body.classList.add('modal-open');
    dialog.current.querySelector('button')?.focus();
    const onKeyDown = event => {
      if (event.key === 'Escape') close.current();
      if (event.key !== 'Tab') return;
      const focusable = [...dialog.current.querySelectorAll(FOCUSABLE)];
      const first = focusable[0], last = focusable.at(-1);
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    };
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      app.inert = false;
      document.body.classList.remove('modal-open');
      if (trigger?.isConnected) trigger.focus();
    };
  }, []);

  return createPortal(
    <div className="modal-backdrop" onClick={event => { if (event.target === event.currentTarget) onClose(); }}>
      <section className="modal" role="dialog" aria-modal="true" aria-label={label} ref={dialog}>
        <button className="icon-btn modal-close" data-action="close-modal" aria-label="닫기" onClick={onClose}><Icon name="close" /></button>
        {children}
      </section>
    </div>,
    document.getElementById('modal-root'),
  );
}
