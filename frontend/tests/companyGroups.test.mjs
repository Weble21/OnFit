import test from 'node:test';
import assert from 'node:assert/strict';
import { expandCompanies, matchesCompany, splitCompanyInterests } from '../src/companyGroups.js';

test('Kakao expands to the reviewed group and deduplicates explicit subsidiaries', () => {
  assert.deepEqual(expandCompanies(['(주)카카오', '카카오페이']), ['카카오', '카카오페이', '카카오뱅크', '카카오모빌리티']);
  assert.equal(matchesCompany('카카오 모빌리티 주식회사', ['카카오']), true);
  assert.equal(matchesCompany('카카오뱅크', ['카카오']), true);
  assert.equal(matchesCompany('카카오와 일하는 개발회사', ['카카오']), false);
  assert.equal(matchesCompany('카카오', ['카카오페이']), false);
});

test('Unknown companies remain exact targets and typed interests support commas', () => {
  assert.deepEqual(splitCompanyInterests('카카오, 네이버\nExample，'), ['카카오', '네이버', 'Example']);
  assert.deepEqual(expandCompanies(['Example']), ['Example']);
  assert.equal(matchesCompany('Example Subsidiary', ['Example']), false);
});
