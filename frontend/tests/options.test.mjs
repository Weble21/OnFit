import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { roles, regions, industries } from '../src/data.js';

const seed = JSON.parse(await readFile(new URL('../../backend/src/main/resources/seed/job-postings.json', import.meta.url), 'utf8'));

test('every seeded role and region can be chosen in the profile form', () => {
  assert.deepEqual(seed.map(job => job.roleName).filter(role => !roles.includes(role)), []);
  assert.deepEqual(seed.map(job => job.location).filter(region => !regions.includes(region)), []);
  assert.deepEqual(seed.map(job => job.companyType).filter(industry => !industries.includes(industry)), []);
});
