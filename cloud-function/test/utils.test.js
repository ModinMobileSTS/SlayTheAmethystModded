'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');

const { summarizeErrorWithCause } = require('../src/utils');

test('summarizeErrorWithCause keeps nested network details', () => {
  const error = new Error('fetch failed');
  error.cause = {
    code: 'ENOTFOUND',
    errno: -3008,
    hostname: 'uploads.github.com',
    message: 'getaddrinfo ENOTFOUND uploads.github.com'
  };

  assert.equal(
    summarizeErrorWithCause(error),
    'fetch failed | code=ENOTFOUND, errno=-3008, host=uploads.github.com, getaddrinfo ENOTFOUND uploads.github.com'
  );
});

test('summarizeErrorWithCause falls back to top-level message', () => {
  assert.equal(summarizeErrorWithCause(new Error('plain failure')), 'plain failure');
});
