// Browser-only settings are scoped to the signed-in account.
export function read(key, fallback, account) {
  if (!account) return fallback;
  try { return JSON.parse(localStorage.getItem('onfit.' + account + '.' + key)) ?? fallback; }
  catch { return fallback; }
}

export function persist(key, value, account) {
  if (!account) return false;
  try { localStorage.setItem('onfit.' + account + '.' + key, JSON.stringify(value)); return true; }
  catch { return false; }
}
