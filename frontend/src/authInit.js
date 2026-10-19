// How keycloak-js starts on page load. Kept free of browser globals so it can be unit tested.

/** Set while this browser has a signed-in session, so later visits know a silent check is worth doing. */
export const SESSION_HINT_KEY = 'onfit.auth.hadSession';

/** keycloak-js gives up on a silent check it never hears back from only after this long. */
export const INIT_TIMEOUT_MS = 15000;

/** How long a returning visitor waits to learn that Keycloak is down before the app says so. */
export const REACHABILITY_TIMEOUT_MS = 5000;

/** Keycloak sends the login result back in the URL fragment (keycloak-js uses response_mode=fragment). */
export const isAuthCallback = hash => /(?:^#|&)state=/.test(hash || '');

/**
 * - Back from Keycloak: keycloak-js reads the result from the URL; nothing else to ask.
 * - Signed in before: ask Keycloak in a hidden iframe instead of leaving the page.
 *   If the browser blocks the iframe's cookies (Keycloak on another site), the page still never leaves:
 *   the visitor starts signed out, and the login button then signs them in without a password prompt.
 * - Otherwise: no request at all, so a first visit never waits for (or depends on) Keycloak.
 */
export function initOptions({ hash, hadSession, redirectUri, silentCheckSsoRedirectUri }) {
  const options = { flow: 'standard', pkceMethod: 'S256', checkLoginIframe: false, redirectUri };
  if (!isAuthCallback(hash) && hadSession) {
    return { ...options, onLoad: 'check-sso', silentCheckSsoRedirectUri, silentCheckSsoFallback: false };
  }
  return options;
}

/**
 * Fails fast when Keycloak cannot be reached: a refused connection rejects at once, a silent one after `ms`.
 * keycloak-js itself would wait 10 seconds on its hidden iframes. `no-cors` because only reachability matters.
 */
export async function assertReachable(url, ms, fetchImpl = fetch) {
  try {
    await fetchImpl(url, { mode: 'no-cors', cache: 'no-store', signal: AbortSignal.timeout(ms) });
  } catch {
    throw new Error('Keycloak is not reachable.');
  }
}

/** Rejects when `promise` takes longer than `ms`; keycloak-js has no timeout of its own for the silent check. */
export function withTimeout(promise, ms, message) {
  let timer;
  const timeout = new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(message)), ms); });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timer));
}
