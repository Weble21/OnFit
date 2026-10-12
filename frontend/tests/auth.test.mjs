import test from 'node:test';
import assert from 'node:assert/strict';
import { assertReachable, initOptions, isAuthCallback, withTimeout } from '../src/authInit.js';

const urls = { redirectUri: 'http://localhost:5173/', silentCheckSsoRedirectUri: 'http://localhost:5173/silent-check-sso.html' };

test('a first visit asks Keycloak nothing, so the page never leaves or waits for it', () => {
  const options = initOptions({ hash: '', hadSession: false, ...urls });
  assert.equal(options.onLoad, undefined);
  assert.equal(options.silentCheckSsoRedirectUri, undefined);
  assert.equal(options.pkceMethod, 'S256');
  assert.equal(initOptions({ hash: '#/profile', hadSession: false, ...urls }).onLoad, undefined);
});

test('a returning browser checks its session in a hidden iframe instead of a redirect', () => {
  const options = initOptions({ hash: '#/recommendations', hadSession: true, ...urls });
  assert.equal(options.onLoad, 'check-sso');
  assert.equal(options.silentCheckSsoRedirectUri, urls.silentCheckSsoRedirectUri);
  // Blocked third-party cookies must not turn the check back into a full-page redirect.
  assert.equal(options.silentCheckSsoFallback, false);
});

test('an unreachable Keycloak is reported quickly instead of after keycloak-js timeouts', async () => {
  await assert.doesNotReject(assertReachable('https://kc.example/', 1000, async () => ({ type: 'opaque' })));
  await assert.rejects(assertReachable('https://kc.example/', 1000, async () => { throw new TypeError('Failed to fetch'); }),
    /not reachable/);
  // A server that never answers: the abort signal ends the wait.
  const hanging = (url, { signal }) => new Promise((_, reject) => signal.addEventListener('abort', () => reject(signal.reason)));
  const start = Date.now();
  await assert.rejects(assertReachable('https://kc.example/', 50, hanging), /not reachable/);
  assert.ok(Date.now() - start < 1000);
});

test('the return from Keycloak is only read, never checked again', () => {
  const callback = '#state=abc&session_state=def&iss=x&code=ghi';
  assert.equal(isAuthCallback(callback), true);
  assert.equal(isAuthCallback('#error=login_required&state=abc'), true);
  assert.equal(isAuthCallback('#/profile'), false);
  assert.equal(isAuthCallback('#/statement'), false);
  assert.equal(initOptions({ hash: callback, hadSession: true, ...urls }).onLoad, undefined);
});

test('a silent check that never answers fails instead of hanging the app', async () => {
  await assert.rejects(withTimeout(new Promise(() => {}), 20, 'no answer'), /no answer/);
  assert.equal(await withTimeout(Promise.resolve(true), 1000, 'no answer'), true);
});
