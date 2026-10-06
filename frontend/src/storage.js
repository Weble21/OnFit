// Browser-only settings. Profile fields the server does not keep (interests, department) live here too.
export function read(key, fallback) {
  try { return JSON.parse(localStorage.getItem('onfit.' + key)) ?? fallback; }
  catch { return fallback; }
}

export function persist(key, value) {
  try { localStorage.setItem('onfit.' + key, JSON.stringify(value)); return true; }
  catch { return false; }
}

export function validProfile(value) {
  return !!value && Array.isArray(value.types) && value.types.every(v => typeof v === 'string') &&
    ['companies', 'role', 'department', 'career'].every(k => typeof value[k] === 'string') &&
    Array.isArray(value.projects) && value.projects.length > 0 &&
    value.projects.every(p => p && ['name', 'description', 'stack'].every(k => typeof p[k] === 'string')) &&
    (value.experiences === undefined || (Array.isArray(value.experiences) &&
      value.experiences.every(e => e && ['company', 'role', 'start', 'end'].every(k => typeof e[k] === 'string'))));
}
