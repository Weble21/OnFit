import test from 'node:test';
import assert from 'node:assert/strict';
import { toProfileRequest, fromProfileResponse, toDisplayJob } from '../src/api.js';

test('profile form converts to backend schema', () => {
  const request = toProfileRequest({ role: '백엔드 개발자', location: '서울', projects: [
    { name: 'API', description: '구현', stack: 'Java, Spring Boot, Java' },
  ] });
  assert.deepEqual(request.targetRoles, ['백엔드 개발자']);
  assert.deepEqual(request.preferredLocations, ['서울']);
  assert.deepEqual(request.skills, ['Java', 'Spring Boot']);
  assert.equal(request.projects[0].techStack, 'Java, Spring Boot, Java');
});

test('backend profile and recommendation map to existing UI', () => {
  const profile = fromProfileResponse({ targetRoles: ['백엔드 개발자'], preferredLocations: ['서울'],
    skills: ['Java'], projects: [{ name: 'API', description: '구현', techStack: 'Java' }] });
  assert.equal(profile.location, '서울');
  assert.equal(profile.projects[0].stack, 'Java');
  const job = toDisplayJob({ id: 1, companyName: '가상 회사', title: '개발자', roleName: '백엔드 개발자',
    companyType: '스타트업', location: '서울', careerLevel: '신입', description: '', responsibilities: 'API 개발',
    requiredSkills: ['Java', 'Docker'], preferredSkills: ['AWS'], status: 'OPEN', deadline: '2027-12-31' },
  { id: 2, totalScore: 52.5, matchedEvidence: ['필수 기술 일치: Java'], missingSkills: ['Docker'] });
  assert.equal(job.id, '1');
  assert.equal(job.recommendationId, 2);
  assert.equal(job.score, 52.5);
  assert.deepEqual(job.requiredMatch.matched, ['Java']);
  assert.deepEqual(job.requiredMatch.missing, ['Docker']);
});

test('experiences, certificates and fields the form does not edit survive a save', () => {
  const profile = fromProfileResponse({ targetRoles: ['백엔드 개발자'], preferredLocations: [], skills: ['Java'],
    certificates: ['정보처리기사'],
    projects: [{ name: 'API', description: '구현', techStack: 'Java', startedOn: '2024-01-01', endedOn: null, projectUrl: 'https://example.com' }],
    experiences: [{ companyName: '가상 회사', roleName: '백엔드 개발자', description: '결제 API', startedOn: '2023-03-01', endedOn: null }] });
  assert.equal(profile.career, '경력');
  assert.deepEqual(profile.experiences[0], { company: '가상 회사', role: '백엔드 개발자', start: '2023-03', end: '', extra: { description: '결제 API' } });

  const request = toProfileRequest(profile);
  assert.deepEqual(request.certificates, ['정보처리기사']);
  assert.deepEqual(request.experiences, [{ description: '결제 API', companyName: '가상 회사', roleName: '백엔드 개발자', startedOn: '2023-03-01', endedOn: null }]);
  assert.equal(request.projects[0].projectUrl, 'https://example.com');
  assert.equal(request.projects[0].startedOn, '2024-01-01');
  assert.deepEqual(toProfileRequest({ ...profile, career: '신입' }).experiences, []);
});
