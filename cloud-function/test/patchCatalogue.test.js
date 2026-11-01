'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { createApp } = require('../src/createApp');
const { parsePatchUpload, publishPatchUpload, ensurePatchUploadsConfigured } = require('../src/patchCatalogue');
const { loadPatchCatalogueConfig } = require('../src/config');
const { metadata, jar, zip, config, githubFixture } = require('../test-support/patchFixtures');

function uploadRequest(data = metadata(), { source, consent = { publicEmail: true, redistribution: true } } = {}) {
  return {
    is: (type) => type === 'multipart/form-data',
    body: { metadata: JSON.stringify(data), consent: JSON.stringify(consent) },
    files: { jar: [{ buffer: jar(data) }], ...(source ? { source: [{ buffer: source }] } : {}) }
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
  await publishPatchUpload(await parse(metadata({ name: 'Known ID' })), config());
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
  const unicode = await parse(metadata({ name: 'Straße' }));
  await publishPatchUpload(unicode, config());
  const afterUnicode = fixture.head();
  await assert.rejects(publishPatchUpload(await parse(metadata({ name: 'STRASSE' })), config()), /collision/);
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
