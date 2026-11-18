'use strict';

const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { mkdtempSync, mkdirSync, rmSync } = require('node:fs');
const path = require('node:path');
const zlib = require('node:zlib');
const crc32 = require('crc-32');

function metadata(overrides = {}) {
  return {
    schemaVersion: 1, name: '测试补丁', description: '补丁简介。', version: '1.0.0',
    author: { name: 'Player', email: 'player@example.com' },
    target: { modId: 'basemod', workshopId: '1605833019', versions: ['5.56.0'] },
    jar: '中文 Patch.jar', ...overrides
  };
}

function zip(entries) {
  const local = [];
  const central = [];
  let offset = 0;
  for (const entry of entries) {
    const name = Buffer.from(entry.name);
    const content = Buffer.from(entry.content || '');
    const compressed = entry.compress ? zlib.deflateRawSync(content) : content;
    const checksum = entry.crc === undefined ? crc32.buf(content) >>> 0 : entry.crc;
    const flags = 0x800 | (entry.flags || 0);
    const header = Buffer.alloc(30);
    header.writeUInt32LE(0x04034b50);
    header.writeUInt16LE(20, 4);
    header.writeUInt16LE(flags, 6);
    header.writeUInt16LE(entry.compress ? 8 : 0, 8);
    header.writeUInt32LE(checksum, 14);
    header.writeUInt32LE(compressed.length, 18);
    header.writeUInt32LE(content.length, 22);
    header.writeUInt16LE(name.length, 26);
    local.push(header, name, compressed);
    const record = Buffer.alloc(46);
    record.writeUInt32LE(0x02014b50);
    record.writeUInt16LE(0x314, 4);
    header.copy(record, 6, 4, 30);
    record.writeUInt32LE(((entry.mode || 0o100644) << 16) >>> 0, 38);
    record.writeUInt32LE(offset, 42);
    central.push(record, name);
    offset += header.length + name.length + compressed.length;
  }
  const directory = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(directory.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, directory, end]);
}

function jar(data = metadata(), extra = [], patchModId = 'test_patch') {
  return zip([
    { name: 'ModTheSpire.json', content: JSON.stringify({
      modid: patchModId, name: data.name, version: data.version, description: data.description,
      dependencies: [data.target.modId]
    }), compress: true },
    { name: 'patch/Test.class', content: Buffer.from('cafebabe00000034', 'hex'), compress: true },
    ...extra
  ]);
}

function config(overrides = {}) {
  return {
    bundleMaxBytes: 1024 * 1024,
    githubAuth: { mode: 'app', cachedInstallationToken: { token: 'installation-token', expiresAtMs: Date.now() + 3600_000 } },
    patchCatalogue: {
      owner: 'owner', repo: 'catalogue', branch: 'main',
      trustProxyHops: 0,
      committerName: 'Catalogue Service', committerEmail: 'service@example.com'
    },
    ...overrides
  };
}

// Exercise the Git Data API adapter against actual Git objects. Every accepted
// ref update must be a fast-forward; read-back checks detect lost/deleted files.
function githubFixture(t, initialFiles = {
  'index.json': '{"schemaVersion":3,"count":0,"mods":{}}\n',
  'README.md': '# Catalogue\n\n<!-- MOD_LIST_START -->\n<!-- MOD_LIST_END -->\n'
}) {
  const base = path.resolve(__dirname, '../../agent-tmp');
  mkdirSync(base, { recursive: true });
  const directory = mkdtempSync(path.join(base, 'patch-upload-test-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const git = (args, input, extraEnv = {}) => execFileSync('git', args, {
    cwd: directory, input, encoding: 'utf8', stdio: ['pipe', 'pipe', 'pipe'],
    env: { ...process.env, GIT_AUTHOR_NAME: 'Fixture', GIT_AUTHOR_EMAIL: 'fixture@example.com',
      GIT_COMMITTER_NAME: 'Fixture', GIT_COMMITTER_EMAIL: 'fixture@example.com', ...extraEnv }
  }).trimEnd();
  git(['init', '--bare', '-q']);
  const blob = (content) => git(['hash-object', '-w', '--stdin'], content);
  const listTree = (sha, recursive = false) => !sha ? [] : git([
    'ls-tree', '-z', ...(recursive ? ['-r', '-t'] : []), sha
  ]).split('\0').filter(Boolean).map((line) => {
    const match = /^(\d+) (\w+) (\w+)\t([\s\S]*)$/.exec(line);
    return { mode: match[1], type: match[2], sha: match[3], path: match[4] };
  });
  const buildTree = (entries, baseTree) => {
    const children = new Map(listTree(baseTree).map((entry) => [entry.path, entry]));
    for (const entry of entries) {
      const [first, ...rest] = entry.path.split('/');
      if (rest.length) {
        const old = children.get(first);
        const sha = buildTree([{ ...entry, path: rest.join('/') }], old && old.sha);
        children.set(first, { path: first, mode: '040000', type: 'tree', sha });
      } else if (entry.sha === null) children.delete(first);
      else children.set(first, entry);
    }
    const input = [...children.values()].map((entry) => `${entry.mode} ${entry.type} ${entry.sha}\t${entry.path}\0`).join('');
    return git(['mktree', '-z'], input);
  };
  const commit = (tree, parent, data = {}) => git(['commit-tree', tree, ...(parent ? ['-p', parent] : [])], `${data.message || 'Fixture'}\n`, {
    ...(data.author ? { GIT_AUTHOR_NAME: data.author.name, GIT_AUTHOR_EMAIL: data.author.email } : {}),
    ...(data.committer ? { GIT_COMMITTER_NAME: data.committer.name, GIT_COMMITTER_EMAIL: data.committer.email } : {})
  });
  const treeOf = (sha) => git(['show', '-s', '--format=%T', sha]);
  const head = () => git(['rev-parse', 'refs/heads/main']);
  const addFiles = (files) => {
    let parent;
    try { parent = head(); } catch (_) { /* Initial commit. */ }
    const tree = buildTree(Object.entries(files).map(([name, content]) => ({
      path: name, mode: '100644', type: 'blob', sha: blob(content)
    })), parent && treeOf(parent));
    const sha = commit(tree, parent);
    git(['update-ref', 'refs/heads/main', sha]);
    return sha;
  };
  addFiles(initialFiles);
  git(['symbolic-ref', 'HEAD', 'refs/heads/main']);
  const calls = [];
  const fixture = { calls, git, head, addFiles, directory, beforeUpdate: null,
    read: (name) => git(['show', `${head()}:${name}`]),
    paths: () => listTree(treeOf(head()), true).filter((entry) => entry.type === 'blob').map((entry) => entry.path)
  };
  const realFetch = global.fetch;
  const mockFetch = async (url, init = {}) => {
    if (!String(url).startsWith('https://api.github.com/')) return realFetch(url, init);
    assert.equal(init.headers.Authorization, 'Bearer installation-token');
    const parsed = new URL(url);
    const route = decodeURIComponent(parsed.pathname.replace('/repos/owner/catalogue', ''));
    const method = init.method || 'GET';
    const body = init.body ? JSON.parse(init.body) : undefined;
    calls.push({ route, method, body });
    let value;
    let status = 200;
    if (route.startsWith('/git/ref/heads/') && method === 'GET') value = { object: { sha: head() } };
    else if (route.startsWith('/git/commits/') && method === 'GET') value = { tree: { sha: treeOf(route.split('/').pop()) } };
    else if (route.startsWith('/git/trees/') && method === 'GET') {
      value = { tree: listTree(route.split('/').pop(), parsed.searchParams.has('recursive')), truncated: false };
    } else if (route.startsWith('/git/blobs/') && method === 'GET') {
      const content = Buffer.from(execFileSync('git', ['cat-file', 'blob', route.split('/').pop()], { cwd: directory }));
      value = { size: content.length, encoding: 'base64', content: content.toString('base64') };
    } else if (route === '/git/blobs' && method === 'POST') {
      value = { sha: blob(Buffer.from(body.content, 'base64')) };
    } else if (route === '/git/trees' && method === 'POST') {
      value = { sha: buildTree(body.tree, body.base_tree) };
    } else if (route === '/git/commits' && method === 'POST') {
      value = { sha: commit(body.tree, body.parents[0], body) };
    } else if (route.startsWith('/git/refs/heads/') && method === 'PATCH') {
      assert.equal(body.force, false);
      const rejection = fixture.beforeUpdate && fixture.beforeUpdate(body);
      if (rejection) {
        status = typeof rejection === 'object' ? rejection.status : rejection;
        if (typeof rejection === 'object' && rejection.apply) git(['update-ref', 'refs/heads/main', body.sha, head()]);
        value = { message: 'Ref rejected' };
      }
      else {
        assert.doesNotThrow(() => git(['merge-base', '--is-ancestor', head(), body.sha]));
        git(['update-ref', 'refs/heads/main', body.sha, head()]);
        value = { object: { sha: head() } };
      }
    } else throw new Error(`Unexpected GitHub request ${method} ${route}`);
    return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } });
  };
  if (t.mock && typeof t.mock.method === 'function') {
    t.mock.method(global, 'fetch', mockFetch);
  } else {
    global.fetch = mockFetch;
    t.after(() => { global.fetch = realFetch; });
  }
  return fixture;
}

module.exports = { metadata, jar, zip, config, githubFixture };
