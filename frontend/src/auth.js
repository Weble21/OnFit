import Keycloak from 'keycloak-js';

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
if (client) client.onAuthLogout = () => window.dispatchEvent(new Event('onfit-auth-logout'));
const routeKey = 'onfit.auth.returnRoute';
const routes = new Set(['#/profile', '#/recommendations', '#/analyze', '#/favorites']);
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
    rememberRoute();
    initialization = client.init({ onLoad: 'check-sso', flow: 'standard', pkceMethod: 'S256',
      checkLoginIframe: false, redirectUri: callbackUrl() }).then(authenticated => {
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
  return client.logout({ redirectUri: callbackUrl() });
}
