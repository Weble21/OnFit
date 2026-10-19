import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './styles.css';
import { App } from './App.jsx';
import { initializeAuth } from './auth.js';
import { setAccessTokenProvider } from './api.js';
import { accessToken } from './auth.js';

setAccessTokenProvider(accessToken);
initializeAuth().then(
  authenticated => createRoot(document.getElementById('app')).render(<StrictMode><App authenticated={authenticated} /></StrictMode>),
  () => createRoot(document.getElementById('app')).render(<StrictMode><App authenticated={false} authError="로그인 서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요." /></StrictMode>),
);
