import test from 'node:test';
import assert from 'node:assert/strict';
import { detectSkills, compareSkills, analyzeText, sampleJD, sampleProfile } from '../src/data.js';
test('skill names do not match substrings of other skills', () => {
  assert.deepEqual(detectSkills('JavaScript PostgreSQL'), ['JavaScript', 'PostgreSQL']);
  assert.deepEqual(detectSkills('NodeXjs'), []);
  assert.deepEqual(detectSkills('Node.js, Java 개발'), ['Java', 'Node.js']);
});
test('unknown requirements stay unknown instead of receiving a perfect score', () => {
  assert.equal(compareSkills([], ['Java']).score, null);
  assert.equal(analyzeText('협업을 잘하는 사람을 찾습니다.', sampleProfile).overall.score, null);
});
test('required and preferred criteria stay separate', () => {
  const result = analyzeText(sampleJD, sampleProfile);
  assert.equal(result.required.score, 100);
  assert.equal(result.preferred.score, 50);
  assert.equal(result.overall.score, 80);
  assert.deepEqual(result.preferred.missing, ['Redis']);
});

