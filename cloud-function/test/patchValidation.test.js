'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const { metadata, jar, zip } = require('../test-support/patchFixtures');
const { parseJson, validateMetadata, validateJar, readSourceArchive } = require('../src/patchValidation');

test('metadata preserves exact case, Unicode names, arbitrary JAR names and wildcard versions', () => {
  const data = metadata();
  data.target.modId = 'ExactModID';
  data.target.versions = ['*'];
  assert.equal(validateMetadata(data), data);
});

test('metadata rejects unsafe paths, versions, identity and unsupported fields', () => {
  const invalid = [
    { name: '../bad' }, { name: 'CON' }, { name: ' bad' }, { name: 'e\u0301' },
    { name: '\u200bbad' }, { jar: 'file.zip' }, { jar: '路径/patch.jar' }, { schemaVersion: true },
    { target: { ...metadata().target, modId: 'INDEX' } },
    { target: { ...metadata().target, workshopId: 123 } },
    { target: { ...metadata().target, versions: ['*', '2'] } },
    { target: { ...metadata().target, versions: ['2', '2'] } },
    { author: { name: 'Player', email: 'not-an-email' } }, { unknown: true }, { example: 'true' }
  ];
  for (const changes of invalid) assert.throws(() => validateMetadata(metadata(changes)), { statusCode: 400 });
});

test('metadata permits omitted Workshop IDs but rejects malformed supplied IDs', async () => {
  const data = metadata();
  delete data.target.workshopId;
  assert.equal(validateMetadata(data), data);
  assert.ok((await validateJar(jar(data), data)).patchModId);
  for (const workshopId of [null, '', '0', 0, 123, '01', '-1', ' 123', 'invalid', '1'.repeat(21)]) {
    assert.throws(() => validateMetadata(metadata({ target: { ...data.target, workshopId } })), /Invalid workshopId/);
  }
});

test('JSON rejects duplicates (including escaped keys), invalid UTF-8 and oversized input', () => {
  for (const input of ['{"x":1,"x":2}', '{"x":1,"\\u0078":2}', Buffer.from([0xff]), ' '.repeat(65537)]) {
    assert.throws(() => parseJson(input, 'test'), { statusCode: 400 });
  }
  assert.deepEqual(parseJson('{"nested":{"x":1},"x":2,"s":"\\\"[]{}"}', 'test'), { nested: { x: 1 }, x: 2, s: '"[]{}' });
});

test('JAR validates all members, manifests and checksum without executing classes', async () => {
  const data = metadata();
  const buffer = jar(data, [{ name: 'agent-patch.json', content: JSON.stringify({
    parent_mod_id: data.target.modId, patch_mod_id: 'test_patch', name: data.name,
    version: data.version, description: data.description, patch_id: 'patch-1'
  }) }]);
  assert.deepEqual(await validateJar(buffer, data), {
    size: buffer.length, sha256: createHash('sha256').update(buffer).digest('hex'), patchModId: 'test_patch'
  });
});

test('JAR rejects corruption, duplicate paths, symlinks, encryption and traversal', async () => {
  const invalid = [
    Buffer.from('not a zip'),
    jar(metadata(), [{ name: 'bad.class', content: 'corrupt', crc: 0 }]),
    jar(metadata(), [{ name: 'patch/Test.class' }]),
    jar(metadata(), [{ name: 'link', content: 'target', mode: 0o120777 }]),
    jar(metadata(), [{ name: 'device', content: '', mode: 0o020600 }]),
    jar(metadata(), [{ name: 'encrypted', flags: 1 }]),
    jar(metadata(), [{ name: '../bad' }]), jar(metadata(), [{ name: 'C:/bad' }]),
    jar(metadata(), [{ name: 'bad\\path' }])
  ];
  for (const buffer of invalid) await assert.rejects(validateJar(buffer, metadata()), { statusCode: 400 });
});

test('JAR rejects missing classes, target dependencies and mismatched manifests', async () => {
  await assert.rejects(validateJar(zip([{ name: 'ModTheSpire.json', content: '{}' }]), metadata()), { statusCode: 400 });
  await assert.rejects(validateJar(jar(), metadata({ description: 'changed' })), /does not match/);
  const data = metadata();
  data.target.modId = 'BaseMod';
  await assert.rejects(validateJar(jar(), data), /case-sensitive/);
  await assert.rejects(validateJar(jar(metadata(), [{ name: 'agent-patch.json', content: '{}' }]), metadata()), /mismatch/);
});

test('source ZIP returns relative src files and rejects file/directory or Unicode case-fold collisions', async () => {
  const files = await readSourceArchive(zip([
    { name: 'pkg/', mode: 0o040755 }, { name: 'pkg/Test.java', content: 'source', compress: true },
    { name: 'metadata.json', content: '{}' }
  ]));
  assert.equal(files.get('pkg/Test.java').toString(), 'source');
  assert.equal(files.get('metadata.json').toString(), '{}');
  for (const entries of [
    [{ name: '../bad' }], [{ name: '.git/config' }], [{ name: 'CON' }],
    [{ name: 'Straße.java' }, { name: 'STRASSE.java' }],
    [{ name: 'PKG/One.java' }, { name: 'pkg/Two.java' }],
    [{ name: 'pkg' }, { name: 'pkg/Test.java' }], [{ name: 'empty/', mode: 0o040755 }],
    [{ name: 'device', mode: 0o020600 }]
  ]) await assert.rejects(readSourceArchive(zip(entries)), { statusCode: 400 });
});
