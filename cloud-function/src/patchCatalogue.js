'use strict';

const { githubFetchJson } = require('./github');
const { httpError, encodePath, encodePathSegment } = require('./utils');
const {
  MAX_JSON_BYTES, requireValid, patchDirectory, parseJson, validateMetadata, validateJar, readSourceArchive
} = require('./patchValidation');

const MAX_COMMIT_ATTEMPTS = 5;

function ensurePatchUploadsConfigured(config) {
  if (!config.patchCatalogue || !config.githubAuth || config.githubAuth.mode !== 'app') {
    throw httpError(503, 'Patch uploads require GitHub App authentication');
  }
}

async function parsePatchUpload(req) {
  requireValid(req.is('multipart/form-data'), 'Expected multipart/form-data');
  const body = req.body || {};
  requireValid(Object.keys(body).every((key) => ['metadata', 'consent'].includes(key)), 'Unknown upload fields');
  requireValid(typeof body.metadata === 'string', 'metadata must be a JSON form field');
  const metadata = validateMetadata(parseJson(body.metadata, 'metadata'));
  requireValid(metadata.example !== true, 'example entries are reserved for catalogue maintainers');
  const consent = typeof body.consent === 'string' ? parseJson(body.consent, 'consent') : null;
  requireValid(consent && typeof consent === 'object' && !Array.isArray(consent) &&
    Object.keys(consent).length === 2 && Object.hasOwn(consent, 'publicEmail') &&
    Object.hasOwn(consent, 'redistribution') && consent.publicEmail === true && consent.redistribution === true,
    'Explicit publicEmail and redistribution consent is required');
  const files = req.files || {};
  const jar = files.jar && files.jar[0];
  requireValid(jar && Buffer.isBuffer(jar.buffer), 'A jar file is required');
  // metadata.jar is authoritative: browsers may encode multipart filenames in
  // Latin-1. Never use those filenames as repository paths.
  const jarInfo = await validateJar(jar.buffer, metadata);
  const source = files.source && files.source[0];
  const sources = source ? await readSourceArchive(source.buffer) : null;
  return { metadata, jar: jar.buffer, jarInfo, sources, path: patchDirectory(metadata) };
}

function repositoryError(message) {
  return httpError(409, message);
}

async function publishPatchUpload(upload, config) {
  const settings = config.patchCatalogue;
  const api = `/repos/${encodePathSegment(settings.owner)}/${encodePathSegment(settings.repo)}`;
  const request = (path, method = 'GET', body) => githubFetchJson(`${api}${path}`, {
    method, ...(body === undefined ? {} : { body: JSON.stringify(body) })
  }, config);
  const { caseFold } = await import('unicode-case-folding');
  const { metadata } = upload;
  const modId = metadata.target.modId;
  const directoryName = upload.path.split('/')[2];

  const getTree = async (sha, recursive = false) => {
    const result = await request(`/git/trees/${encodePathSegment(sha)}${recursive ? '?recursive=1' : ''}`);
    if (!result || result.truncated || !Array.isArray(result.tree)) {
      throw httpError(502, 'GitHub returned an incomplete catalogue tree');
    }
    return result.tree;
  };
  const treeEntry = (tree, name) => {
    const entry = tree.find((item) => item.path === name);
    if (entry && (entry.type !== 'tree' || entry.mode !== '040000')) {
      throw repositoryError(`Expected a directory at ${name}`);
    }
    return entry;
  };
  const checkCase = (names, expected) => {
    if (names.some((name) => name !== expected && caseFold(name) === caseFold(expected))) {
      throw repositoryError(`Case-insensitive catalogue path collision: ${expected}`);
    }
  };
  const readMetadata = async (sha) => {
    const blob = await request(`/git/blobs/${encodePathSegment(sha)}`);
    if (blob.encoding !== 'base64' || blob.size > MAX_JSON_BYTES || typeof blob.content !== 'string') {
      throw repositoryError('Existing metadata blob is invalid or exceeds limit');
    }
    try {
      return validateMetadata(parseJson(Buffer.from(blob.content, 'base64'), 'existing metadata'));
    } catch (error) {
      throw repositoryError(error.message);
    }
  };
  const createTree = (tree, baseTree) => request('/git/trees', 'POST', {
    ...(baseTree ? { base_tree: baseTree } : {}), tree
  });
  const createBlob = async (buffer) => {
    const blob = await request('/git/blobs', 'POST', { encoding: 'base64', content: buffer.toString('base64') });
    return blob.sha;
  };

  // Upload bytes once. On ref races only trees/commits are rebuilt from new HEAD.
  let additions;
  for (let attempt = 0; attempt < MAX_COMMIT_ATTEMPTS; attempt += 1) {
    const ref = await request(`/git/ref/heads/${encodePath(settings.branch)}`);
    const headSha = ref.object.sha;
    const head = await request(`/git/commits/${encodePathSegment(headSha)}`);
    const rootTree = await getTree(head.tree.sha);
    const mods = treeEntry(rootTree, 'mods');
    const modsTree = mods ? await getTree(mods.sha) : [];
    if (modsTree.some((entry) => entry.type !== 'tree' || entry.mode !== '040000')) {
      throw repositoryError('mods/ must contain only modid directories');
    }
    checkCase(modsTree.map((item) => item.path), modId);
    const mod = treeEntry(modsTree, modId);
    const modTree = mod ? await getTree(mod.sha, true) : [];
    const topLevel = modTree.filter((entry) => !entry.path.includes('/'));
    checkCase(topLevel.map((entry) => entry.path), directoryName);
    const patch = treeEntry(topLevel, directoryName);
    let previousMetadata = null;

    // Read committed metadata rather than the asynchronously generated index.
    // This also catches Workshop conflicts when two first uploads race.
    for (const entry of topLevel) {
      if (entry.type !== 'tree' || entry.mode !== '040000') {
        throw repositoryError(`Unexpected file in mods/${modId}: ${entry.path}`);
      }
      const record = modTree.find((item) => item.path === `${entry.path}/metadata.json`);
      if (!record || record.type !== 'blob' || record.mode !== '100644') {
        throw repositoryError(`Missing regular metadata.json in ${entry.path}`);
      }
      const existing = await readMetadata(record.sha);
      if (patchDirectory(existing) !== `mods/${modId}/${entry.path}`) {
        throw repositoryError('Existing metadata does not match its directory');
      }
      if (existing.target.workshopId !== metadata.target.workshopId) {
        throw repositoryError(`Conflicting Workshop IDs for ${modId}`);
      }
      if (entry.path === directoryName) previousMetadata = existing;
    }
    const previousFiles = patch ? modTree.filter((entry) =>
      entry.path.startsWith(`${directoryName}/`) && !entry.path.slice(directoryName.length + 1).includes('/')
    ).map((entry) => ({ ...entry, path: entry.path.slice(directoryName.length + 1) })) : [];
    for (const entry of modTree.filter((item) => item.path.startsWith(`${directoryName}/`))) {
      if (!['100644', '100755', '040000'].includes(entry.mode)) {
        throw repositoryError('Existing patch contains symlinks or unsupported file modes');
      }
    }
    const jarCollision = previousFiles.find((entry) => caseFold(entry.path) === caseFold(metadata.jar) &&
      entry.path !== metadata.jar && (!previousMetadata || entry.path !== previousMetadata.jar));
    if (jarCollision) throw repositoryError('JAR filename collides with an existing file');
    if (previousFiles.some((entry) => entry.path === metadata.jar && entry.type !== 'blob')) {
      throw repositoryError('JAR filename collides with a directory');
    }
    if (previousMetadata) {
      const oldJar = previousFiles.find((entry) => entry.path === previousMetadata.jar);
      if (!oldJar || oldJar.type !== 'blob' || oldJar.mode !== '100644') {
        throw repositoryError('Existing metadata JAR is missing or not a regular file');
      }
    }

    if (!additions) {
      const metadataBytes = Buffer.from(`${JSON.stringify(metadata, null, 2)}\n`);
      const metadataSha = await createBlob(metadataBytes);
      const jarSha = await createBlob(upload.jar);
      additions = [
        { path: 'metadata.json', mode: '100644', type: 'blob', sha: metadataSha },
        { path: metadata.jar, mode: '100644', type: 'blob', sha: jarSha }
      ];
      if (upload.sources) {
        const files = await Promise.all(Array.from(upload.sources.entries()).map(async ([path, buffer]) => ({
          path, mode: '100644', type: 'blob', sha: await createBlob(buffer)
        })));
        const sourceTree = await createTree(files);
        additions.push({ path: 'src', mode: '040000', type: 'tree', sha: sourceTree.sha });
      }
    }
    const changes = [...additions];
    if (previousMetadata && previousMetadata.jar !== metadata.jar) {
      if (previousFiles.some((entry) => entry.path === previousMetadata.jar)) {
        changes.push({ path: previousMetadata.jar, mode: '100644', type: 'blob', sha: null });
      }
    }
    const patchTree = await createTree(changes, patch && patch.sha);
    const updatedModTree = await createTree([
      { path: directoryName, mode: '040000', type: 'tree', sha: patchTree.sha }
    ], mod && mod.sha);
    const updatedModsTree = await createTree([
      { path: modId, mode: '040000', type: 'tree', sha: updatedModTree.sha }
    ], mods && mods.sha);
    const tree = await createTree([
      { path: 'mods', mode: '040000', type: 'tree', sha: updatedModsTree.sha }
    ], head.tree.sha);
    const commit = await request('/git/commits', 'POST', {
      message: `${metadata.author.name} <${metadata.author.email}>`,
      author: { name: metadata.author.name, email: metadata.author.email },
      committer: { name: settings.committerName, email: settings.committerEmail },
      tree: tree.sha,
      parents: [headSha]
    });
    try {
      await request(`/git/refs/heads/${encodePath(settings.branch)}`, 'PATCH', { sha: commit.sha, force: false });
    } catch (error) {
      // Resolve a lost response if the non-forced ref update actually landed.
      const latest = await request(`/git/ref/heads/${encodePath(settings.branch)}`);
      if (latest.object.sha === commit.sha) {
        return buildUploadResult(commit.sha, upload, settings);
      }
      // A 422 can also mean branch protection. Retry only if HEAD really moved.
      if (![409, 422].includes(error.githubStatus)) throw error;
      if (latest.object.sha === headSha) throw error;
      if (attempt + 1 < MAX_COMMIT_ATTEMPTS) continue;
      throw httpError(409, 'Catalogue changed during all upload attempts; please retry');
    }
    return buildUploadResult(commit.sha, upload, settings);
  }
  throw httpError(409, 'Catalogue changed during upload; please retry');
}

function buildUploadResult(commitSha, upload, settings) {
  const repository = `${settings.owner}/${settings.repo}`;
  const rawBase = `https://raw.githubusercontent.com/${repository}`;
  return {
    commitSha,
    commitUrl: `https://github.com/${repository}/commit/${commitSha}`,
    path: upload.path,
    indexUrl: `${rawBase}/${encodePathSegment(settings.branch)}/index.json`,
    indexing: 'pending',
    download_url: `${rawBase}/${commitSha}/${encodeRawPath(`${upload.path}/${upload.metadata.jar}`)}`,
    ...upload.jarInfo
  };
}

function encodeRawPath(path) {
  return String(path).split('/').map((segment) => encodeURIComponent(segment)
    .replace(/[!'()*]/g, (character) => `%${character.charCodeAt(0).toString(16).toUpperCase()}`)).join('/');
}

// Per-instance protection; a shared gateway/WAF quota is required across SCF instances.
function createPatchUploadGuard(config) {
  const recent = new Map();
  let active = 0;
  return (req, res, next) => {
    try {
      ensurePatchUploadsConfigured(config);
      const now = Date.now();
      for (const [key, value] of recent) if (now - value.start >= 60_000) recent.delete(key);
      const key = String(req.ip || req.socket.remoteAddress || 'unknown');
      const quota = recent.get(key) || { start: now, count: 0 };
      if (quota.count >= 5 || active >= 2) {
        res.set('Retry-After', '60');
        throw httpError(429, 'Patch upload rate or concurrency limit exceeded');
      }
      quota.count += 1;
      recent.set(key, quota);
      active += 1;
      let released = false;
      req.releasePatchUpload = () => {
        if (!released) { released = true; active -= 1; }
      };
      res.once('finish', req.releasePatchUpload);
      res.once('close', () => {
        if (!req.patchUploadProcessing) req.releasePatchUpload();
      });
      next();
    } catch (error) {
      next(error);
    }
  };
}

module.exports = { ensurePatchUploadsConfigured, parsePatchUpload, publishPatchUpload, createPatchUploadGuard };
