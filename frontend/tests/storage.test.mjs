import test from 'node:test';
import assert from 'node:assert/strict';
import { persist, read } from '../src/storage.js';

test('browser settings do not cross accounts or reuse legacy demo data', () => {
  const original = globalThis.localStorage;
  const data = new Map([['onfit.profile', JSON.stringify({ role: '옛 데모 프로필' })]]);
  globalThis.localStorage = {
    getItem: key => data.get(key) ?? null,
    setItem: (key, value) => data.set(key, value),
  };
  try {
    assert.equal(persist('favorites', ['job-a'], 'issuer:user-a'), true);
    assert.deepEqual(read('favorites', [], 'issuer:user-a'), ['job-a']);
    assert.deepEqual(read('favorites', [], 'issuer:user-b'), []);
    assert.equal(read('profile', null, 'issuer:user-a'), null);
    assert.equal(read('favorites', [], null).length, 0);
  } finally {
    globalThis.localStorage = original;
  }
});
