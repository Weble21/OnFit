import { profileSkills } from './data.js';

const BACKEND_DOWN = '백엔드에 연결할 수 없습니다. PostgreSQL과 Spring Boot를 실행해 주세요.';
let accessTokenProvider = async () => { throw new Error('로그인이 필요합니다.'); };
export function setAccessTokenProvider(provider) { accessTokenProvider = provider; }

async function request(path, options = {}) {
  const token = await accessTokenProvider();
  let response;
  try {
    response = await fetch('/api' + path, {
      ...options,
      headers: { 'Accept': 'application/json', Authorization: `Bearer ${token}`,
        ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...options.headers },
    });
  } catch (error) {
    if (error.name === 'AbortError') throw error;
    throw new Error(BACKEND_DOWN);
  }
  if (!response.ok) {
    // The backend answers with an RFC 9457 problem whose `detail` is written for users.
    const problem = await response.json().catch(() => null);
    const fallback = response.status === 404 ? '요청한 데이터를 찾을 수 없습니다.'
      : response.status === 502 ? BACKEND_DOWN : '서버 요청에 실패했습니다.';
    const error = new Error(problem?.detail || fallback);
    error.status = response.status;
    throw error;
  }
  return response.json();
}

export const getProfile = () => request('/profiles/me');
export async function saveProfile(profile) {
  const body = JSON.stringify(toProfileRequest(profile));
  // Updating is the common case; only the first save needs to create the profile.
  try { return await request('/profiles/me', { method: 'PUT', body }); }
  catch (error) {
    if (error.status !== 404) throw error;
    return request('/profiles', { method: 'POST', body });
  }
}

/** Same rule as the backend: CRLF/CR become LF, and spaces and control characters at both ends go. */
export const normalizeJobText = text => text.replace(/\r\n?/g, '\n').replace(/^[\x00-\x20]+|[\x00-\x20]+$/g, '');

/** SHA-256 of the normalized text the user confirmed; the server refuses text that differs from it. */
export async function textHash(text) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(normalizeJobText(text)));
  return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('');
}

/**
 * The server opens the posting address and returns its text as a draft to review, like an uploaded file.
 * The answer arrives as JSON lines: progress events ({ stage, done, total }) go to onProgress, then the draft.
 */
export async function extractJobDraftFromUrl(url, signal, onProgress = () => {}) {
  const token = await accessTokenProvider();
  let response;
  try {
    response = await fetch('/api/job-analysis/url', { method: 'POST', body: JSON.stringify({ url }), signal,
      headers: { Accept: 'application/x-ndjson, application/json', 'Content-Type': 'application/json', Authorization: `Bearer ${token}` } });
  } catch (error) {
    if (error.name === 'AbortError') throw error;
    throw new Error(BACKEND_DOWN);
  }
  if (!response.ok) {
    // Refused before streaming started (bad address, login): an ordinary problem response.
    const problem = await response.json().catch(() => null);
    const error = new Error(problem?.detail || (response.status === 502 ? BACKEND_DOWN : '공고를 가져오지 못했습니다.'));
    error.status = response.status;
    throw error;
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffered = '';
  for (;;) {
    const { value, done } = await reader.read();
    buffered += decoder.decode(value, { stream: !done });
    let newline;
    while ((newline = buffered.indexOf('\n')) >= 0) {
      const line = buffered.slice(0, newline).trim();
      buffered = buffered.slice(newline + 1);
      if (!line) continue;
      const event = JSON.parse(line);
      if (event.type === 'progress') onProgress(event);
      else if (event.type === 'draft') return event.draft;
      else if (event.type === 'error') {
        const error = new Error(event.detail || '공고를 가져오지 못했습니다.');
        error.status = event.status;
        throw error;
      }
    }
    if (done) throw new Error('공고를 가져오는 중 연결이 끊어졌습니다. 다시 시도해 주세요.');
  }
}

/** body: { inputType: 'TEXT', text } or { inputType: 'FILE' | 'URL', text, draftId, confirmedTextHash }. */
export const analyzeJob = (body, signal) => request('/job-analysis/text', { method: 'POST', body: JSON.stringify(body), signal });
export const retryRoleAnalysis = id => request(`/job-analysis/${encodeURIComponent(id)}/role-analysis`, { method: 'POST' });

/** Uploads a posting file and returns the extracted text as a draft to review. Nothing is scored yet. */
export async function extractJobDraft(file, signal) {
  const token = await accessTokenProvider();
  const body = new FormData();
  body.append('file', file);
  let response;
  try {
    response = await fetch('/api/job-analysis/file', { method: 'POST', body, signal,
      headers: { Accept: 'application/json', Authorization: `Bearer ${token}` } });
  } catch (error) {
    if (error.name === 'AbortError') throw error;
    throw new Error(BACKEND_DOWN);
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(problem?.detail || (response.status === 502 ? BACKEND_DOWN : '파일에서 텍스트를 추출하지 못했습니다.'));
  }
  return response.json();
}

export function toProfileRequest(profile) {
  // PUT replaces the whole profile, so fields the form does not edit travel in `extra` and go back unchanged.
  return {
    targetRoles: profile.roles,
    preferredLocations: profile.location ? [profile.location.trim()] : [],
    skills: profileSkills(profile),
    certificates: (profile.certificates || []).map(certificate => ({
      name: certificate.name, category: certificate.category,
      acquiredOn: certificate.acquiredOn || null, score: certificate.score || null,
    })),
    projects: profile.projects.map(project => ({
      ...project.extra, name: project.name, description: project.description, techStack: project.stack,
    })),
    experiences: profile.career === '경력' ? (profile.experiences || []).map(experience => ({
      ...experience.extra, companyName: experience.company, roleName: experience.role,
      startedOn: experience.start + '-01', endedOn: experience.end ? experience.end + '-01' : null,
    })) : [],
  };
}

export function fromProfileResponse(response, previous = null) {
  const experiences = (response.experiences || []).map(experience => ({
    company: experience.companyName, role: experience.roleName,
    start: experience.startedOn.slice(0, 7), end: experience.endedOn ? experience.endedOn.slice(0, 7) : '',
    extra: { description: experience.description },
  }));
  return {
    roles: response.targetRoles.length ? response.targetRoles : ['백엔드 개발자'],
    location: response.preferredLocations[0] || '',
    career: experiences.length ? '경력' : previous?.career || '신입',
    experiences,
    certificates: (response.certificates || []).map(certificate => ({
      name: certificate.name, category: certificate.category || 'OTHER',
      acquiredOn: certificate.acquiredOn || '', score: certificate.score || '',
    })),
    projects: response.projects.length ? response.projects.map(project => ({
      name: project.name, description: project.description || '', stack: project.techStack || '',
      extra: { startedOn: project.startedOn, endedOn: project.endedOn, projectUrl: project.projectUrl },
    })) : [{ name: '', description: '', stack: response.skills.join(', ') }],
  };
}

