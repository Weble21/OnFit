import Keycloak from 'keycloak-js';
import { INIT_TIMEOUT_MS, REACHABILITY_TIMEOUT_MS, SESSION_HINT_KEY, assertReachable, initOptions, withTimeout } from './authInit.js';

const local = ['localhost', '127.0.0.1'].includes(location.hostname);
const settings = {
  url: (import.meta.env?.VITE_KEYCLOAK_URL || (local ? 'http://localhost:8081' : ''))?.replace(/\/$/, ''),
  realm: import.meta.env?.VITE_KEYCLOAK_REALM || (local ? 'onfit' : ''),
  clientId: import.meta.env?.VITE_KEYCLOAK_CLIENT_ID || (local ? 'onfit-web' : ''),
};

export const authConfigured = Boolean(settings.url && settings.realm && settings.clientId);
const providerAliases = new Set(['google', 'naver', 'kakao']);
export const socialProviders = new Set(
  (import.meta.env?.VITE_SOCIAL_PROVIDERS || '').split(',').map(value => value.trim().toLowerCase())
    .filter(value => providerAliases.has(value)),
);
const client = authConfigured ? new Keycloak(settings) : null;
let initialization;

function hadSession() {
  try { return localStorage.getItem(SESSION_HINT_KEY) === '1'; }
  catch { return false; }
}

function rememberSession(signedIn) {
  try { if (signedIn) localStorage.setItem(SESSION_HINT_KEY, '1'); else localStorage.removeItem(SESSION_HINT_KEY); }
  catch { /* Without storage every visit simply starts signed out until the user logs in. */ }
}

if (client) {
  client.onAuthLogout = () => {
    rememberSession(false);
    window.dispatchEvent(new Event('onfit-auth-logout'));
  };
}
const routeKey = 'onfit.auth.returnRoute';
const routes = new Set(['#/profile', '#/recommendations', '#/analyze', '#/favorites', '#/alerts']);
const callbackUrl = () => location.origin + location.pathname;

function rememberRoute() {
  try { if (routes.has(location.hash)) sessionStorage.setItem(routeKey, location.hash); }
  catch { /* Browsers may disable session storage; login still returns to the landing page. */ }
}

function restoreRoute() {
  try {
    const route = sessionStorage.getItem(routeKey);
    sessionStorage.removeItem(routeKey);
    if (routes.has(route) && (!location.hash || location.hash === '#/')) location.hash = route;
  } catch { /* Session storage is optional. */ }
}

export function initializeAuth() {
  if (!client) return Promise.resolve(false);
  if (!initialization) {
    // If the browser blocks third-party cookies, keycloak-js falls back to a full redirect; keep the screen.
    rememberRoute();
    const options = initOptions({ hash: location.hash, hadSession: hadSession(), redirectUri: callbackUrl(),
      silentCheckSsoRedirectUri: location.origin + '/silent-check-sso.html' });
    // Only the session check talks to Keycloak during startup, so only it needs to know Keycloak is up.
    const reachable = options.onLoad
      ? assertReachable(`${settings.url}/realms/${settings.realm}/.well-known/openid-configuration`, REACHABILITY_TIMEOUT_MS)
      : Promise.resolve();
    initialization = reachable
      .then(() => withTimeout(client.init(options), INIT_TIMEOUT_MS, 'Keycloak did not answer in time.'))
      .then(authenticated => {
        rememberSession(authenticated);
        restoreRoute();
        return authenticated;
      });
  }
  return initialization;
}

export function accountId() {
  return client?.authenticated ? `${settings.url}/realms/${settings.realm}:${client.subject}` : null;
}

const accountClaims = () => (client?.authenticated ? client.idTokenParsed || client.tokenParsed : null);

/**
 * Label for the signed-in account. The name the social provider shows comes first: Kakao and Naver send
 * no real name, and Google's display name keeps Korean order ("김동건"), unlike Keycloak's "first last".
 * Then the Keycloak profile name, then the email.
 */
export function accountName() {
  const claims = accountClaims();
  if (!claims) return null;
  const name = claims.name?.trim() || [claims.given_name, claims.family_name].filter(Boolean).join(' ').trim();
  return claims.nickname?.trim() || name || claims.email || null;
}

/** Profile photo from the social provider, if any. Only https URLs are used as an image source. */
export function accountPicture() {
  const picture = accountClaims()?.picture;
  return typeof picture === 'string' && /^https:\/\//i.test(picture) ? picture : null;
}

export async function accessToken() {
  if (!client?.authenticated) throw new Error('로그인이 필요합니다.');
  try {
    await client.updateToken(30);
  } catch {
    client.clearToken();
    throw new Error('로그인이 만료되었습니다. 다시 로그인해 주세요.');
  }
  if (!client.token) throw new Error('로그인이 만료되었습니다. 다시 로그인해 주세요.');
  return client.token;
}

export function login(provider) {
  if (!client) throw new Error('Keycloak 설정이 필요합니다.');
  if (provider && !socialProviders.has(provider)) throw new Error('선택한 소셜 로그인이 설정되지 않았습니다.');
  rememberRoute();
  return client.login({ redirectUri: callbackUrl(), ...(provider ? { idpHint: provider } : {}) });
}

export function logout() {
  if (!client) return Promise.resolve();
  rememberSession(false);
  return client.logout({ redirectUri: callbackUrl() });
}
