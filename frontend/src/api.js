import { profileSkills } from './data.js';

const BACKEND_DOWN = '백엔드에 연결할 수 없습니다. PostgreSQL과 Spring Boot를 실행해 주세요.';

async function request(path, options = {}) {
  let response;
  try {
    response = await fetch('/api' + path, {
      ...options,
      headers: { 'Accept': 'application/json', ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
    });
  } catch {
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
export const createRecommendations = () => request('/recommendations', { method: 'POST' });

export function toProfileRequest(profile) {
  // PUT replaces the whole profile, so fields the form does not edit travel in `extra` and go back unchanged.
  return {
    targetRoles: [profile.role],
    preferredLocations: profile.location ? [profile.location.trim()] : [],
    skills: profileSkills(profile),
    certificates: profile.certificates || [],
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
    types: previous?.types || [], companies: previous?.companies || '',
    role: response.targetRoles[0] || '백엔드 개발자',
    location: response.preferredLocations[0] || '',
    department: previous?.department || '',
    career: experiences.length ? '경력' : previous?.career || '신입',
    experiences,
    certificates: response.certificates || [],
    projects: response.projects.length ? response.projects.map(project => ({
      name: project.name, description: project.description || '', stack: project.techStack || '',
      extra: { startedOn: project.startedOn, endedOn: project.endedOn, projectUrl: project.projectUrl },
    })) : [{ name: '', description: '', stack: response.skills.join(', ') }],
  };
}

export function toDisplayJob(job, recommendation = null) {
  return {
    id: String(job.id),
    company: job.companyName, initial: job.companyName.slice(0, 1), color: 'sage',
    type: job.companyType || '기타', role: job.roleName, team: job.roleName,
    location: job.location, career: job.careerLevel || '경력 무관', title: job.title,
    intro: job.description || '', duties: job.responsibilities,
    required: job.requiredSkills, preferred: job.preferredSkills,
    status: job.status, deadline: job.deadline,
    score: recommendation?.totalScore ?? null,
    scores: recommendation,
    requiredMatch: { matched: recommendation?.matchedRequiredSkills || [], missing: recommendation?.missingSkills || [] },
    preferredMatch: { matched: recommendation?.matchedPreferredSkills || [] },
  };
}
