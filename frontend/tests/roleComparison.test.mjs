import test from 'node:test';
import assert from 'node:assert/strict';
import { compareRoles, parseComparisonRoles, rankComparison } from '../src/roleComparison.js';

const result = (total, noRequirements = false) => ({ score: { total, noRequirements } });

test('requires two to five distinct posting roles', () => {
  assert.deepEqual(parseComparisonRoles(' IT개발 \n 정보보호 ').roles, ['IT개발', '정보보호']);
  assert.ok(parseComparisonRoles('IT개발').error);
  assert.ok(parseComparisonRoles('IT 개발\nIT개발').error);
  assert.ok(parseComparisonRoles('a\nb\nc\nd\ne\nf').error);
});

test('ranks equal scores together and excludes failures and unscored roles', () => {
  const ranked = rankComparison([
    { role: 'failed', error: '오류' }, { role: 'low', result: result(30) },
    { role: 'high', result: result(80) }, { role: 'tie', result: result(80) },
    { role: 'no information', result: result(null, true) }, { role: 'middle', result: result(50) },
  ]);
  assert.deepEqual(ranked.map(item => [item.role, item.rank]), [
    ['high', 1], ['tie', 1], ['middle', 3], ['low', 4], ['failed', null], ['no information', null],
  ]);
});

test('separates unmet eligibility and uses work, technology, then preference to break ties', () => {
  const scored = (total, eligibility, work, technology, userPreference, evidenceLevel = '높음') => ({ score: {
    total, noRequirements: false, rubric: { eligibility, evidenceLevel, categories: [
      { key: 'work', points: work }, { key: 'technology', points: technology },
      { key: 'userPreference', points: userPreference },
    ] },
  } });
  const ranked = rankComparison([
    { role: 'ineligible', result: scored(95, '미충족', 35, 25, 5) },
    { role: 'conditional', result: scored(82, '확인 필요', 30, 20, 0, '낮음') },
    { role: 'stronger work', result: scored(82, '충족', 32, 18, 0) },
    { role: 'lower', result: scored(80, '충족', 29, 20, 0) },
  ]);
  assert.deepEqual(ranked.map(item => [item.role, item.rank]), [
    ['stronger work', 1], ['conditional', 2], ['lower', 3], ['ineligible', null],
  ]);
  assert.equal(ranked[1].provisional, true);
  assert.equal(ranked[2].similarFit, true);
});

test('compares the same reviewed posting separately, preserving partial errors', async () => {
  const calls = [], updates = [];
  const signal = new AbortController().signal;
  const entries = await compareRoles(['개발', '보안'], { inputType: 'FILE', text: 'reviewed', draftId: 3, confirmedTextHash: 'hash' },
    async (body, actualSignal) => {
      calls.push(body); assert.equal(actualSignal, signal);
      if (body.targetRole === '보안') throw new Error('직무를 구분하지 못했습니다.');
      return result(75);
    }, signal, (done, total) => updates.push([done.length, total]));
  assert.deepEqual(calls.map(item => [item.targetRole, item.text, item.draftId]), [['개발', 'reviewed', 3], ['보안', 'reviewed', 3]]);
  assert.equal(entries[1].error, '직무를 구분하지 못했습니다.');
  assert.deepEqual(updates, [[1, 2], [2, 2]]);
});

test('unverified evidence and conditional eligibility do not block numeric score ranking', () => {
  const pending = total => ({ score: { total, noRequirements: false,
    rubric: { pending: true, eligibility: '확인 필요', categories: [] } } });
  const ranked = rankComparison([
    { role: 'AX', result: pending(32) }, { role: 'backend', result: pending(78.2) },
  ]);
  assert.deepEqual(ranked.map(item => [item.role, item.rank]), [['backend', 1], ['AX', 2]]);
});

test('cancellation stops the remaining role requests', async () => {
  const controller = new AbortController();
  let calls = 0;
  await assert.rejects(compareRoles(['개발', '보안'], {}, async () => {
    calls++; controller.abort(); return result(80);
  }, controller.signal), { name: 'AbortError' });
  assert.equal(calls, 1);
});
