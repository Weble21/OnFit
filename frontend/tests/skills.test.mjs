import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { skillAliases, normalizeSkill, splitSkills, profileSkills } from '../src/data.js';
import { toProfileRequest } from '../src/api.js';

const backendAliases = JSON.parse(await readFile(new URL('../../backend/src/main/resources/skill-aliases.json', import.meta.url), 'utf8'));

test('skill aliases match the backend table', () => {
  assert.deepEqual(skillAliases, backendAliases);
});

test('stacks split on commas, full-width commas and semicolons but keep CI/CD whole', () => {
  assert.deepEqual(splitSkills('Java，Spring Boot; CI/CD,  ,Docker'), ['Java', 'Spring Boot', 'CI/CD', 'Docker']);
});

test('aliases the backend treats as duplicates are sent once', () => {
  assert.equal(normalizeSkill('  Amazon   Web Services '), 'aws');
  const profile = { role: '백엔드 개발자', location: '', projects: [
    { name: 'A', description: '', stack: 'AWS, k8s' }, { name: 'B', description: '', stack: 'Amazon Web Services, Kubernetes' },
  ] };
  assert.deepEqual(profileSkills(profile), ['AWS', 'k8s']);
  assert.deepEqual(toProfileRequest(profile).skills, ['AWS', 'k8s']);
});
