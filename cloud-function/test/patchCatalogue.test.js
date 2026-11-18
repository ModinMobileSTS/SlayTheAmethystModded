'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { createApp } = require('../src/createApp');
const { parsePatchUpload, publishPatchUpload, ensurePatchUploadsConfigured } = require('../src/patchCatalogue');
const { loadPatchCatalogueConfig } = require('../src/config');
const { metadata, jar, zip, config, githubFixture } = require('../test-support/patchFixtures');

function uploadRequest(data = metadata(), { source, patchModId, consent = { publicEmail: true, redistribution: true } } = {}) {
  return {
    is: (type) => type === 'multipart/form-data',
    body: { metadata: JSON.stringify(data), consent: JSON.stringify(consent) },
    files: { jar: [{ buffer: jar(data, [], patchModId) }], ...(source ? { source: [{ buffer: source }] } : {}) }
  };
}

function parse(data, options) {
  return parsePatchUpload(uploadRequest(data, options));
}

test('anonymous upload is enabled by default and proxy trust is opt-in', (t) => {
  const previous = process.env;
  process.env = {};
  t.after(() => { process.env = previous; });
  const result = loadPatchCatalogueConfig();
  assert.equal(result.repo, 'SlaytheAmethystPlayerModCatalogue');
  assert.equal(result.trustProxyHops, 0);
  process.env.PATCH_UPLOAD_TRUST_PROXY_HOPS = '2';
  assert.equal(loadPatchCatalogueConfig().trustProxyHops, 2);
  process.env.PATCH_UPLOAD_TRUST_PROXY_HOPS = '6';
  assert.throws(loadPatchCatalogueConfig, /PATCH_UPLOAD_TRUST_PROXY_HOPS/);
  process.env.PATCH_UPLOAD_TRUST_PROXY_HOPS = '2junk';
  assert.throws(loadPatchCatalogueConfig, /PATCH_UPLOAD_TRUST_PROXY_HOPS/);
});

test('anonymous IP throttling ignores forwarding headers unless proxy hops are configured', () => {
  const settings = config();
  assert.equal(createApp(settings).get('trust proxy'), 0);
  assert.equal(createApp({ ...settings, patchCatalogue: { ...settings.patchCatalogue, trustProxyHops: 1 } })
    .get('trust proxy'), 1);
});

test('anonymous upload does not require bearer/shared-secret and still requires GitHub App', () => {
  const settings = config();
  assert.doesNotThrow(() => ensurePatchUploadsConfigured(settings));
  assert.throws(() => ensurePatchUploadsConfigured({ ...settings, patchCatalogue: null }), { statusCode: 503 });
  assert.throws(() => ensurePatchUploadsConfigured({ ...settings, githubAuth: { mode: 'token' } }), { statusCode: 503 });
});

test('anonymous upload still requires explicit privacy and redistribution consent', async () => {
  await assert.rejects(parse(metadata(), { consent: { publicEmail: false, redistribution: true } }), /consent/);
  await assert.rejects(parse(metadata({ example: true })), /reserved/);
  const req = uploadRequest();
  delete req.files.jar;
  await assert.rejects(parsePatchUpload(req), /jar file is required/);
});

test('one upload publishes one reachable commit with exact author/message and complete files', async (t) => {
  const fixture = githubFixture(t);
  const before = fixture.head();
  const data = metadata();
  const upload = await parse(data, { source: zip([{ name: 'pkg/Test.java', content: 'source' }]) });
  const result = await publishPatchUpload(upload, config());
  assert.equal(fixture.git(['rev-list', '--count', `${before}..${fixture.head()}`]), '1');
  assert.equal(fixture.git(['show', '-s', '--format=%s', fixture.head()]), 'Player <player@example.com>');
  assert.equal(fixture.git(['show', '-s', '--format=%an <%ae>|%cn <%ce>', fixture.head()]),
    'Player <player@example.com>|Catalogue Service <service@example.com>');
  assert.deepEqual(JSON.parse(fixture.read(`${upload.path}/metadata.json`)), data);
  assert.equal(fixture.read(`${upload.path}/src/pkg/Test.java`), 'source');
  assert.equal(fixture.read('index.json'), '{"schemaVersion":3,"count":0,"mods":{}}');
  assert.equal(result.indexing, 'pending');
  assert.equal(result.commitSha, fixture.head());
  assert.equal(result.indexUrl, 'https://raw.githubusercontent.com/owner/catalogue/main/index.json');
  assert.ok(result.download_url.includes(`/${result.commitSha}/mods/basemod/`));
  assert.ok(result.download_url.endsWith('/%E4%B8%AD%E6%96%87%20Patch.jar'));
  assert.equal(fixture.calls.filter((call) => call.method === 'PATCH').length, 1);
  assert.ok(fixture.calls.every((call) => !call.route.includes('/contents/')));
});

test('uploads without Workshop IDs coexist with known IDs and allow adding or omitting the ID on overwrite', async (t) => {
  const fixture = githubFixture(t);
  const data = metadata();
  delete data.target.workshopId;
  const local = await parse(data);
  await publishPatchUpload(local, config());
  assert.deepEqual(JSON.parse(fixture.read(`${local.path}/metadata.json`)), data);
  await publishPatchUpload(await parse(metadata({ name: 'Known ID' }), { patchModId: 'known_patch' }), config());
  await publishPatchUpload(await parse(metadata()), config());
  await publishPatchUpload(local, config());
  const conflicting = metadata({ name: 'Conflicting', target: { ...data.target, workshopId: '123' } });
  await assert.rejects(publishPatchUpload(await parse(conflicting), config()), /Workshop/);
});

test('identical uploads still produce one new commit each; JAR rename deletes old bytes and preserves omitted source', async (t) => {
  const fixture = githubFixture(t);
  const first = await parse(metadata(), { source: zip([{ name: 'old.java', content: 'old source' }]) });
  await publishPatchUpload(first, config());
  const before = fixture.head();
  await publishPatchUpload(first, config());
  assert.notEqual(fixture.head(), before);
  assert.equal(fixture.git(['rev-list', '--count', `${before}..${fixture.head()}`]), '1');
  const data = metadata({ jar: 'Replacement.jar' });
  const second = await parse(data);
  await publishPatchUpload(second, config());
  assert.ok(!fixture.paths().includes(`${first.path}/${first.metadata.jar}`));
  assert.ok(fixture.paths().includes(`${second.path}/${second.metadata.jar}`));
  assert.equal(fixture.read(`${first.path}/src/old.java`), 'old source');
});

test('source ZIP replaces src atomically and removes stale source files', async (t) => {
  const fixture = githubFixture(t);
  const first = await parse(metadata(), { source: zip([{ name: 'old.java', content: 'old' }]) });
  await publishPatchUpload(first, config());
  const second = await parse(metadata(), { source: zip([{ name: 'new.java', content: 'new' }]) });
  await publishPatchUpload(second, config());
  assert.ok(!fixture.paths().includes(`${first.path}/src/old.java`));
  assert.equal(fixture.read(`${first.path}/src/new.java`), 'new');
});

test('patch ID overwrite migrates renamed patch/author atomically and preserves omitted source and extra files', async (t) => {
  const fixture = githubFixture(t);
  const first = await parse(metadata(), { source: zip([{ name: 'old.java', content: 'old source' }]) });
  await publishPatchUpload(first, config());
  fixture.addFiles({ [`${first.path}/notes.txt`]: 'keep me' });
  const unrelated = await parse(metadata({ name: 'Unrelated' }), { patchModId: 'unrelated_patch' });
  await publishPatchUpload(unrelated, config());
  const before = fixture.head();
  const data = metadata({
    name: 'Renamed Patch', version: '2.0.0', jar: 'Updated.jar',
    author: { name: 'New Player', email: 'new@example.com' }
  });
  const update = await parse(data);
  const result = await publishPatchUpload(update, config());
  assert.equal(fixture.git(['rev-list', '--count', `${before}..${fixture.head()}`]), '1');
  assert.equal(result.path, update.path);
  assert.ok(!fixture.paths().some((name) => name.startsWith(`${first.path}/`)));
  assert.deepEqual(JSON.parse(fixture.read(`${update.path}/metadata.json`)), data);
  assert.ok(fixture.paths().includes(`${update.path}/Updated.jar`));
  assert.ok(!fixture.paths().includes(`${update.path}/${first.metadata.jar}`));
  assert.equal(fixture.read(`${update.path}/src/old.java`), 'old source');
  assert.equal(fixture.read(`${update.path}/notes.txt`), 'keep me');
  assert.deepEqual(JSON.parse(fixture.read(`${unrelated.path}/metadata.json`)), unrelated.metadata);
  assert.equal(fixture.git(['show', '-s', '--format=%an <%ae>', fixture.head()]), 'New Player <new@example.com>');
});

test('changing only the author or patch name also overwrites by ID, including case-only renames', async (t) => {
  const fixture = githubFixture(t);
  let previous = await parse(metadata(), { source: zip([{ name: 'old.java', content: 'old source' }]) });
  await publishPatchUpload(previous, config());
  for (const data of [
    metadata({ author: { name: 'Other Player', email: 'other@example.com' } }),
    metadata({ name: 'Renamed' }),
    metadata({ name: 'RENAMED' })
  ]) {
    const update = await parse(data, { source: zip([{ name: 'new.java', content: data.name }]) });
    await publishPatchUpload(update, config());
    assert.ok(!fixture.paths().some((name) => name.startsWith(`${previous.path}/`)));
    assert.deepEqual(JSON.parse(fixture.read(`${update.path}/metadata.json`)), data);
    assert.equal(fixture.paths().filter((name) => name.endsWith('/metadata.json')).length, 1);
    assert.equal(fixture.read(`${update.path}/src/new.java`), data.name);
    assert.ok(!fixture.paths().includes(`${update.path}/src/old.java`));
    previous = update;
  }
});

test('different patch IDs cannot overwrite the same destination, even when moving an existing ID', async (t) => {
  const fixture = githubFixture(t);
  const first = await parse();
  const other = await parse(metadata({ name: 'Other' }), { patchModId: 'other_patch' });
  await publishPatchUpload(first, config());
  await publishPatchUpload(other, config());
  const before = fixture.head();
  await assert.rejects(publishPatchUpload(await parse(metadata(), { patchModId: 'new_patch' }), config()),
    /different patchModId/);
  await assert.rejects(publishPatchUpload(await parse(metadata({ name: 'Other' })), config()),
    /different patchModId/);
  assert.equal(fixture.head(), before);
  assert.deepEqual(JSON.parse(fixture.read(`${first.path}/metadata.json`)), first.metadata);
  assert.deepEqual(JSON.parse(fixture.read(`${other.path}/metadata.json`)), other.metadata);
});

test('patch ID matching is exact and scoped to the target mod ID', async (t) => {
  const fixture = githubFixture(t);
  const first = await parse();
  await publishPatchUpload(first, config());
  const otherTarget = await parse(metadata({ target: { modId: 'othermod', versions: ['*'] } }));
  await publishPatchUpload(otherTarget, config());
  const otherCase = await parse(metadata({ name: 'Different case ID' }), { patchModId: 'TEST_PATCH' });
  await publishPatchUpload(otherCase, config());
  assert.equal(fixture.paths().filter((name) => name.endsWith('/metadata.json')).length, 3);
  for (const upload of [first, otherTarget, otherCase]) {
    assert.deepEqual(JSON.parse(fixture.read(`${upload.path}/metadata.json`)), upload.metadata);
  }
});

test('ambiguous legacy duplicate IDs fail without silently deleting either patch', async (t) => {
  const fixture = githubFixture(t);
  const first = await parse();
  await publishPatchUpload(first, config());
  const duplicate = await parse(metadata({ name: 'Legacy duplicate' }));
  fixture.addFiles({
    [`${duplicate.path}/metadata.json`]: JSON.stringify(duplicate.metadata),
    [`${duplicate.path}/${duplicate.metadata.jar}`]: duplicate.jar
  });
  const before = fixture.head();
  await assert.rejects(publishPatchUpload(await parse(metadata({ name: 'Updated' })), config()),
    /Multiple existing patches have patchModId/);
  assert.equal(fixture.head(), before);
  assert.equal(fixture.paths().filter((name) => name.endsWith('/metadata.json')).length, 2);
});

test('invalid legacy JARs cannot be silently replaced using only a matching path', async (t) => {
  const fixture = githubFixture(t);
  const first = await parse();
  await publishPatchUpload(first, config());
  fixture.addFiles({ [`${first.path}/${first.metadata.jar}`]: 'not a jar' });
  const before = fixture.head();
  await assert.rejects(publishPatchUpload(first, config()), /Invalid existing JAR/);
  assert.equal(fixture.head(), before);
});

test('ref retry resolves an ID introduced concurrently under a different directory', async (t) => {
  const fixture = githubFixture(t);
  const existing = await parse();
  const update = await parse(metadata({ name: 'Renamed', version: '2.0.0' }));
  let races = 0;
  fixture.beforeUpdate = () => {
    if (races++ > 0) return 0;
    fixture.addFiles({
      [`${existing.path}/metadata.json`]: JSON.stringify(existing.metadata),
      [`${existing.path}/${existing.metadata.jar}`]: existing.jar,
      [`${existing.path}/src/concurrent.java`]: 'keep concurrent source'
    });
    return 422;
  };
  await publishPatchUpload(update, config());
  assert.ok(!fixture.paths().some((name) => name.startsWith(`${existing.path}/`)));
  assert.deepEqual(JSON.parse(fixture.read(`${update.path}/metadata.json`)), update.metadata);
  assert.equal(fixture.read(`${update.path}/src/concurrent.java`), 'keep concurrent source');
  assert.equal(fixture.paths().filter((name) => name.endsWith('/metadata.json')).length, 1);
  assert.equal(fixture.calls.filter((call) => call.method === 'PATCH').length, 2);
  assert.equal(fixture.calls.filter((call) => call.method === 'POST' && call.route === '/git/blobs').length, 2);
});

test('ref race rebases onto latest HEAD and preserves concurrent files without uploading blobs twice', async (t) => {
  const fixture = githubFixture(t);
  const before = fixture.head();
  let races = 0;
  fixture.beforeUpdate = () => {
    if (races++ > 0) return 0;
    fixture.addFiles({ 'concurrent.txt': 'keep me' });
    return 422;
  };
  const upload = await parse();
  await publishPatchUpload(upload, config());
  assert.equal(fixture.read('concurrent.txt'), 'keep me');
  assert.equal(fixture.git(['rev-list', '--count', `${before}..${fixture.head()}`]), '2');
  assert.equal(fixture.calls.filter((call) => call.method === 'POST' && call.route === '/git/blobs').length, 2);
  assert.equal(fixture.calls.filter((call) => call.method === 'POST' && call.route === '/git/commits').length, 2);
});

test('ref races are bounded and branch-protection rejection is not blindly retried', async (t) => {
  const fixture = githubFixture(t);
  const upload = await parse();
  fixture.beforeUpdate = () => 422;
  await assert.rejects(publishPatchUpload(upload, config()), { githubStatus: 422 });
  assert.equal(fixture.calls.filter((call) => call.method === 'PATCH').length, 1);
  fixture.calls.length = 0;
  let count = 0;
  fixture.beforeUpdate = () => {
    fixture.addFiles({ 'racing.txt': String(++count) });
    return 409;
  };
  await assert.rejects(publishPatchUpload(upload, config()), { statusCode: 409 });
  assert.equal(fixture.calls.filter((call) => call.method === 'PATCH').length, 5);
  assert.ok(!fixture.paths().some((name) => name.startsWith('mods/')));
});

test('a lost successful ref-update response is resolved without creating another upload commit', async (t) => {
  const fixture = githubFixture(t);
  const before = fixture.head();
  let rejectResponse = true;
  fixture.beforeUpdate = () => {
    if (!rejectResponse) return 0;
    rejectResponse = false;
    return { status: 599, apply: true };
  };
  const upload = await parse();
  const result = await publishPatchUpload(upload, config());
  assert.notEqual(result.commitSha, before);
  assert.equal(result.commitSha, fixture.head());
  assert.equal(fixture.git(['rev-list', '--count', `${before}..${fixture.head()}`]), '1');
});

test('anonymous overwrite, Workshop conflicts and Unicode case-folded path aliases', async (t) => {
  const fixture = githubFixture(t);
  const data = metadata();
  const upload = await parse(data);
  await publishPatchUpload(upload, config());
  const before = fixture.head();
  const changedIdentity = { ...data, author: { name: 'Player', email: 'someone-else@example.com' } };
  const anonymousOverwrite = await parse(changedIdentity);
  await publishPatchUpload(anonymousOverwrite, config());
  assert.deepEqual(JSON.parse(fixture.read(`${upload.path}/metadata.json`)).author, changedIdentity.author);
  const changedWorkshop = metadata({ name: 'Another', target: { ...data.target, workshopId: '123' } });
  await assert.rejects(publishPatchUpload(await parse(changedWorkshop), config()), /Workshop/);
  const changedCase = metadata({ target: { ...data.target, modId: 'BaseMod' } });
  await assert.rejects(publishPatchUpload(await parse(changedCase), config()), /collision/);
  const unicode = await parse(metadata({ name: 'Straße' }), { patchModId: 'unicode_patch' });
  await publishPatchUpload(unicode, config());
  const afterUnicode = fixture.head();
  await assert.rejects(publishPatchUpload(await parse(metadata({ name: 'STRASSE' }), { patchModId: 'other_patch' }), config()), /collision/);
  assert.equal(fixture.head(), afterUnicode);
  assert.notEqual(before, afterUnicode);
});

test('retry rechecks Workshop metadata introduced by a concurrent upload', async (t) => {
  const fixture = githubFixture(t);
  const upload = await parse();
  fixture.beforeUpdate = () => {
    const existing = metadata({ target: { ...metadata().target, workshopId: '123' } });
    fixture.addFiles({ [`${upload.path}/metadata.json`]: JSON.stringify(existing) });
    return 422;
  };
  await assert.rejects(publishPatchUpload(upload, config()), /Workshop/);
  assert.equal(fixture.calls.filter((call) => call.method === 'PATCH').length, 1);
  assert.ok(!fixture.paths().includes(`${upload.path}/${upload.metadata.jar}`));
});

async function listen(t, app) {
  const server = await new Promise((resolve) => {
    const value = app.listen(0, '127.0.0.1', () => resolve(value));
  });
  t.after(() => { server.closeAllConnections(); server.close(); });
  return `http://127.0.0.1:${server.address().port}`;
}

function form(data = metadata()) {
  const result = new FormData();
  result.set('metadata', JSON.stringify(data));
  result.set('consent', JSON.stringify({ publicEmail: true, redistribution: true }));
  result.set('jar', new Blob([jar(data)]), data.jar);
  return result;
}

test('HTTP multipart route accepts anonymous upload and returns async index status', async (t) => {
  const fixture = githubFixture(t);
  const settings = config();
  const baseUrl = await listen(t, createApp(settings));
  const uploadForm = form();
  uploadForm.set('source', new Blob([zip([{ name: 'Example.java', content: 'source' }])]), 'source.zip');
  const response = await fetch(`${baseUrl}/api/mod-patches/upload`, { method: 'POST', body: uploadForm });
  assert.equal(response.status, 201);
  const body = await response.json();
  assert.equal(body.ok, true);
  assert.equal(body.commitSha, fixture.head());
  assert.equal(body.indexing, 'pending');
  assert.equal(fixture.read('mods/basemod/测试补丁-Player/src/Example.java'), 'source');
});

test('HTTP multipart route accepts a local mod without a Workshop ID', async (t) => {
  const fixture = githubFixture(t);
  const baseUrl = await listen(t, createApp(config()));
  const data = metadata();
  delete data.target.workshopId;
  const response = await fetch(`${baseUrl}/api/mod-patches/upload`, { method: 'POST', body: form(data) });
  assert.equal(response.status, 201);
  const body = await response.json();
  assert.equal(body.ok, true);
  const stored = JSON.parse(fixture.read(`${body.path}/metadata.json`));
  assert.deepEqual(stored.target, data.target);
  assert.ok(!Object.hasOwn(stored.target, 'workshopId'));
});

test('HTTP rejects unexpected/oversized fields and enforces anonymous per-IP quota', async (t) => {
  const settings = config();
  const baseUrl = await listen(t, createApp(settings));
  const extraFile = form();
  extraFile.set('unexpected', new Blob(['bad']), 'bad');
  let response = await fetch(`${baseUrl}/api/mod-patches/upload`, { method: 'POST', body: extraFile });
  assert.equal(response.status, 400);
  const oversized = form();
  oversized.set('metadata', 'x'.repeat(65537));
  response = await fetch(`${baseUrl}/api/mod-patches/upload`, { method: 'POST', body: oversized });
  assert.equal(response.status, 413);
  for (let i = 0; i < 3; i += 1) {
    response = await fetch(`${baseUrl}/api/mod-patches/upload`, { method: 'POST', body: new FormData() });
    assert.equal(response.status, 400);
  }
  response = await fetch(`${baseUrl}/api/mod-patches/upload`, { method: 'POST', body: new FormData() });
  assert.equal(response.status, 429);
  assert.equal(response.headers.get('retry-after'), '60');
});
