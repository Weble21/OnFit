import test from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { toProfileRequest, fromProfileResponse, saveProfile, extractJobDraft, setAccessTokenProvider, getProfile,
  analyzeJob, retryRoleAnalysis, textHash, normalizeJobText } from '../src/api.js';

setAccessTokenProvider(async () => 'test-access-token');

test('private API sends a bearer token and blocks calls without authentication', async () => {
  const original = globalThis.fetch;
  globalThis.fetch = async (_url, options) => {
    assert.equal(options.headers.Authorization, 'Bearer test-access-token');
    return Response.json({ targetRoles: [], preferredLocations: [], skills: [], projects: [] });
  };
  try {
    await getProfile();
    setAccessTokenProvider(async () => { throw new Error('로그인이 필요합니다.'); });
    await assert.rejects(getProfile(), { message: '로그인이 필요합니다.' });
  } finally {
    globalThis.fetch = original;
    setAccessTokenProvider(async () => 'test-access-token');
  }
});

test('profile form converts to backend schema', () => {
  const request = toProfileRequest({ roles: ['백엔드 개발자', 'DevOps 엔지니어'], location: '서울', projects: [
    { name: 'API', description: '구현', stack: 'Java, Spring Boot, Java' },
  ] });
  assert.deepEqual(request.targetRoles, ['백엔드 개발자', 'DevOps 엔지니어']);
  assert.deepEqual(request.preferredLocations, ['서울']);
  assert.deepEqual(request.skills, ['Java', 'Spring Boot']);
  assert.equal(request.projects[0].techStack, 'Java, Spring Boot, Java');
});

test('backend profile maps to the profile form', () => {
  const profile = fromProfileResponse({ targetRoles: ['백엔드 개발자', '데이터 엔지니어'], preferredLocations: ['서울'],
    skills: ['Java'], projects: [{ name: 'API', description: '구현', techStack: 'Java' }] },
  { types: ['핀테크', '금융/핀테크', '클라우드'], companies: '', career: '신입' });
  assert.deepEqual(profile.roles, ['백엔드 개발자', '데이터 엔지니어']);
  assert.equal(profile.location, '서울');
  assert.equal(profile.projects[0].stack, 'Java');

});

test('experiences, certificates and fields the form does not edit survive a save', () => {
  const profile = fromProfileResponse({ targetRoles: ['백엔드 개발자'], preferredLocations: [], skills: ['Java'],
    certificates: [{ id: 7, name: '정보처리기사', category: 'TECHNICAL', acquiredOn: '2024-06-14', score: null }],
    projects: [{ name: 'API', description: '구현', techStack: 'Java', startedOn: '2024-01-01', endedOn: null, projectUrl: 'https://example.com' }],
    experiences: [{ companyName: '가상 회사', roleName: '백엔드 개발자', description: '결제 API', startedOn: '2023-03-01', endedOn: null }] });
  assert.equal(profile.career, '경력');
  assert.deepEqual(profile.experiences[0], { company: '가상 회사', role: '백엔드 개발자', start: '2023-03', end: '', extra: { description: '결제 API' } });

  const request = toProfileRequest(profile);
  // The form shows empty strings for missing values; the request sends them back as null.
  assert.deepEqual(profile.certificates, [{ name: '정보처리기사', category: 'TECHNICAL', acquiredOn: '2024-06-14', score: '' }]);
  assert.deepEqual(request.certificates, [{ name: '정보처리기사', category: 'TECHNICAL', acquiredOn: '2024-06-14', score: null }]);
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
    await assert.rejects(saveProfile({ roles: ['백엔드 개발자'], location: '', projects: [{ name: 'A', description: '', stack: 'Java' }] }),
      { message: '중복된 기술입니다: Postgres', status: 400 });
    globalThis.fetch = async () => new Response('{"error":"Backend unavailable"}', { status: 502 });
    await assert.rejects(saveProfile({ roles: ['백엔드 개발자'], location: '', projects: [{ name: 'A', description: '', stack: 'Java' }] }),
      { message: /백엔드에 연결할 수 없습니다/ });
  } finally {
    globalThis.fetch = original;
  }
});

test('file extraction uploads multipart bytes, returns a draft and exposes server errors', async () => {
  const original = globalThis.fetch;
  const file = new File(['%PDF-1.4'], 'posting.pdf', { type: 'application/pdf' });
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/job-analysis/file');
    assert.equal(options.method, 'POST');
    assert.equal(options.headers['Content-Type'], undefined);
    assert.equal(options.headers.Authorization, 'Bearer test-access-token');
    assert.equal(options.body.get('file').name, 'posting.pdf');
    return Response.json({ draftId: 3, text: 'Java Spring Boot 채용공고', method: 'PDF_TEXT', pages: 1, truncated: false });
  };
  try {
    const draft = await extractJobDraft(file);
    assert.equal(draft.method, 'PDF_TEXT');
    assert.equal(draft.draftId, 3);
    globalThis.fetch = async () => Response.json({ detail: '손상된 PDF입니다.' }, { status: 422 });
    await assert.rejects(extractJobDraft(file), { message: '손상된 PDF입니다.' });
  } finally {
    globalThis.fetch = original;
  }
});

test('confirmed text hash matches the backend rule for the same text', async () => {
  assert.equal(normalizeJobText('\r\n  공고\r\n본문\r \t'), '공고\n본문');
  const expected = createHash('sha256').update('공고\n본문', 'utf8').digest('hex');
  assert.equal(await textHash('  공고\r\n본문\n'), expected);
  assert.notEqual(await textHash('공고\n본문 수정'), expected);
});

test('analysis requests carry only the reviewed text and pass aborts through', async () => {
  const original = globalThis.fetch;
  const sent = [];
  globalThis.fetch = async (url, options) => {
    sent.push([url, options.method, options.body && JSON.parse(options.body)]);
    return Response.json({ id: 9, roleStatus: 'COMPLETED' });
  };
  try {
    const body = { inputType: 'FILE', text: '공고 텍스트', draftId: 3, confirmedTextHash: 'a'.repeat(64) };
    assert.equal((await analyzeJob(body)).id, 9);
    await retryRoleAnalysis(9);
    assert.deepEqual(sent, [['/api/job-analysis/text', 'POST', body], ['/api/job-analysis/9/role-analysis', 'POST', undefined]]);

    globalThis.fetch = async (_url, options) => {
      assert.ok(options.signal.aborted);
      throw new DOMException('aborted', 'AbortError');
    };
    const controller = new AbortController();
    controller.abort();
    await assert.rejects(analyzeJob(body, controller.signal), { name: 'AbortError' });

    globalThis.fetch = async () => Response.json({ detail: 'AI 분석 시간이 초과되었습니다. 잠시 후 다시 시도해 주세요.' }, { status: 504 });
    await assert.rejects(analyzeJob(body), { message: /AI 분석 시간이 초과/, status: 504 });
  } finally {
    globalThis.fetch = original;
  }
});
