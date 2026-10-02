import test from 'node:test';
import assert from 'node:assert/strict';
import { toProfileRequest, fromProfileResponse, toDisplayJob, saveProfile, extractJobText } from '../src/api.js';

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
    industry: '핀테크', companySize: '중견기업', location: '서울', careerLevel: '신입', description: '', responsibilities: 'API 개발',
    requiredSkills: ['Java', 'Docker'], preferredSkills: ['AWS'], status: 'OPEN', deadline: '2027-12-31' },
  { id: 2, totalScore: 52.5, matchedRequiredSkills: ['Java'], matchedPreferredSkills: ['AWS'],
    matchedEvidence: ['필수 기술 일치: Java', '우대 기술 일치: AWS'], missingSkills: ['Docker'] });
  assert.equal(job.id, '1');
  assert.equal(job.industry, '핀테크');
  assert.equal(job.companySize, '중견기업');
  assert.equal(job.score, 52.5);
  assert.deepEqual(job.requiredMatch.matched, ['Java']);
  assert.deepEqual(job.requiredMatch.missing, ['Docker']);
  assert.deepEqual(job.preferredMatch.matched, ['AWS']);
  assert.equal(toDisplayJob({ id: 3, companyName: '가상 회사', roleName: '백엔드 개발자',
    industry: '게임', companySize: null, requiredSkills: [], preferredSkills: [] }).companySize, null);
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

test('server problem details reach the user instead of a generic message', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async () => new Response(JSON.stringify({ status: 400, detail: '중복된 기술입니다: Postgres' }),
    { status: 400, headers: { 'Content-Type': 'application/problem+json' } });
  try {
    await assert.rejects(saveProfile({ role: '백엔드 개발자', location: '', projects: [{ name: 'A', description: '', stack: 'Java' }] }),
      { message: '중복된 기술입니다: Postgres', status: 400 });
    globalThis.fetch = async () => new Response('{"error":"Backend unavailable"}', { status: 502 });
    await assert.rejects(saveProfile({ role: '백엔드 개발자', location: '', projects: [{ name: 'A', description: '', stack: 'Java' }] }),
      { message: /백엔드에 연결할 수 없습니다/ });
  } finally {
    globalThis.fetch = original;
  }
});

test('file extraction uploads multipart bytes and exposes server errors', async () => {
  const original = globalThis.fetch;
  const file = new File(['%PDF-1.4'], 'posting.pdf', { type: 'application/pdf' });
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/job-text/extract');
    assert.equal(options.method, 'POST');
    assert.equal(options.headers['Content-Type'], undefined);
    assert.equal(options.body.get('file').name, 'posting.pdf');
    return Response.json({ text: 'Java Spring Boot 채용공고', method: 'PDF_TEXT', pages: 1, truncated: false });
  };
  try {
    assert.equal((await extractJobText(file)).method, 'PDF_TEXT');
    globalThis.fetch = async () => Response.json({ detail: '손상된 PDF입니다.' }, { status: 422 });
    await assert.rejects(extractJobText(file), { message: '손상된 PDF입니다.' });
  } finally {
    globalThis.fetch = original;
  }
});
