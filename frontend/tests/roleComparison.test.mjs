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

test('cancellation stops the remaining role requests', async () => {
  const controller = new AbortController();
  let calls = 0;
  await assert.rejects(compareRoles(['개발', '보안'], {}, async () => {
    calls++; controller.abort(); return result(80);
  }, controller.signal), { name: 'AbortError' });
  assert.equal(calls, 1);
});
