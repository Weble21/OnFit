async function request(path, options = {}) {
  let response;
  try {
    response = await fetch('/api' + path, {
      ...options,
      headers: { 'Accept': 'application/json', ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
    });
  } catch {
    throw new Error('백엔드에 연결할 수 없습니다. PostgreSQL과 Spring Boot를 실행해 주세요.');
  }
  if (!response.ok) {
    const error = new Error(response.status === 404 ? '요청한 데이터를 찾을 수 없습니다.' : '서버 요청에 실패했습니다.');
    error.status = response.status;
    throw error;
  }
  return response.json();
}

export const getProfile = () => request('/profiles/me');
export async function saveProfile(profile) {
  const body = JSON.stringify(toProfileRequest(profile));
  let exists = false;
  try { await getProfile(); exists = true; }
  catch (error) { if (error.status !== 404) throw error; }
  return request('/profiles' + (exists ? '/me' : ''), { method: exists ? 'PUT' : 'POST', body });
}
export const getJobs = () => request('/jobs');
export const getJob = id => request('/jobs/' + encodeURIComponent(id));
export const createRecommendations = () => request('/recommendations', { method: 'POST' });
export const getRecommendation = id => request('/recommendations/' + encodeURIComponent(id));

export function toProfileRequest(profile) {
  const normalizeSkill = value => {
    const key = value.toLowerCase().replace(/\s+/g, '');
    return ({ postgres: 'postgresql', postgresql: 'postgresql', springboot: 'springboot' })[key] || key;
  };
  const skills = [...new Map(profile.projects.flatMap(project => project.stack.split(/[,，\n]/))
    .map(value => value.trim()).filter(Boolean)
    .map(value => [normalizeSkill(value), value])).values()];
  // PUT replaces the whole profile, so fields the form does not edit travel in `extra` and go back unchanged.
  return {
    targetRoles: [profile.role],
    preferredLocations: profile.location ? [profile.location.trim()] : [],
    skills,
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
  const matched = recommendation?.matchedEvidence || [];
  const matchedRequired = job.requiredSkills.filter(skill => matched.includes('필수 기술 일치: ' + skill));
  const matchedPreferred = job.preferredSkills.filter(skill => matched.includes('우대 기술 일치: ' + skill));
  return {
    id: String(job.id), recommendationId: recommendation?.id ?? null,
    company: job.companyName, initial: job.companyName.slice(0, 1), color: 'sage',
    type: job.companyType || '기타', role: job.roleName, team: job.roleName,
    location: job.location, career: job.careerLevel || '경력 무관', title: job.title,
    intro: job.description || '', duties: job.responsibilities,
    required: job.requiredSkills, preferred: job.preferredSkills,
    status: job.status, deadline: job.deadline,
    score: recommendation?.totalScore ?? null,
    scores: recommendation,
    requiredMatch: { matched: matchedRequired, missing: recommendation?.missingSkills || [] },
    preferredMatch: { matched: matchedPreferred },
  };
}
