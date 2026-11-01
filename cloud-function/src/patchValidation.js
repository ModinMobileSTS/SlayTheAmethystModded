'use strict';

const { createHash } = require('node:crypto');
const { TextDecoder } = require('node:util');
const yauzl = require('yauzl');
const crc32 = require('crc-32');
const { httpError } = require('./utils');

const MAX_JSON_BYTES = 64 * 1024;
const MAX_JAR_BYTES = 25 * 1024 * 1024;
const MAX_SOURCE_BYTES = 10 * 1024 * 1024;
const MAX_SOURCE_EXPANDED_BYTES = 25 * 1024 * 1024;
const MAX_ENTRIES = 4096;
const MAX_JAR_EXPANDED_BYTES = 512 * 1024 * 1024;

function requireValid(condition, message) {
  if (!condition) throw httpError(400, message);
}

function objectFields(value, required, optional = []) {
  requireValid(value && typeof value === 'object' && !Array.isArray(value), 'Expected a JSON object');
  requireValid(required.every((key) => Object.hasOwn(value, key)), 'Missing metadata fields');
  requireValid(Object.keys(value).every((key) => required.includes(key) || optional.includes(key)), 'Unknown metadata fields');
  return value;
}

function text(value, label, maximum = 120, multiline = false) {
  requireValid(typeof value === 'string' && [...value].length > 0 && [...value].length <= maximum &&
    value === value.trim() && value === value.normalize('NFC'), `Invalid ${label} length, whitespace or Unicode NFC`);
  const checked = multiline ? value.replace(/[\n\t]/g, '') : value;
  requireValid(!/\p{C}/u.test(checked), `${label} contains control/format characters`);
  return value;
}

function pathSegment(value, label, maximum = 120) {
  text(value, label, maximum);
  requireValid(!/[\/\\<>:"|?*]/u.test(value) && !value.startsWith('.') && !value.endsWith('.'), `Unsafe ${label} path segment`);
  requireValid(!/^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|$)/i.test(value), `Reserved ${label} path segment`);
  requireValid(Buffer.byteLength(value) <= 240, `${label} exceeds 240 UTF-8 bytes`);
  return value;
}

function patchDirectory(metadata) {
  const segment = `${metadata.name}-${metadata.author.name}`;
  requireValid(Buffer.byteLength(segment) <= 240, 'name-author directory exceeds 240 UTF-8 bytes');
  return `mods/${metadata.target.modId}/${segment}`;
}

function validateMetadata(metadata) {
  objectFields(metadata, ['schemaVersion', 'name', 'description', 'version', 'author', 'target', 'jar'], ['example']);
  requireValid(metadata.schemaVersion === 1, 'Unsupported schemaVersion');
  pathSegment(metadata.name, 'name');
  text(metadata.description, 'description', 4000, true);
  text(metadata.version, 'version');
  objectFields(metadata.author, ['name', 'email']);
  pathSegment(metadata.author.name, 'author.name');
  text(metadata.author.email, 'author.email', 254);
  requireValid(/^[^\s<>@]+@[^\s<>@]+\.[^\s<>@]+$/u.test(metadata.author.email), 'Invalid author.email');
  objectFields(metadata.target, ['modId', 'versions'], ['workshopId']);
  pathSegment(metadata.target.modId, 'target.modId');
  requireValid(metadata.target.modId.toLowerCase() !== 'index', "modId 'index' is reserved");
  if (Object.hasOwn(metadata.target, 'workshopId')) {
    requireValid(typeof metadata.target.workshopId === 'string' && /^[1-9][0-9]{0,19}$/.test(metadata.target.workshopId), 'Invalid workshopId');
  }
  const versions = metadata.target.versions;
  requireValid(Array.isArray(versions) && versions.length >= 1 && versions.length <= 100, 'Invalid target.versions');
  versions.forEach((version) => text(version, 'target version'));
  requireValid(new Set(versions).size === versions.length, 'Duplicate target versions');
  requireValid(!versions.includes('*') || versions.length === 1, 'Wildcard must be the only target version');
  pathSegment(metadata.jar, 'jar');
  requireValid(metadata.jar.toLowerCase().endsWith('.jar'), 'jar must be a .jar filename');
  requireValid(!Object.hasOwn(metadata, 'example') || typeof metadata.example === 'boolean', 'example must be a boolean');
  patchDirectory(metadata);
  return metadata;
}

// JSON.parse alone silently accepts duplicate keys. Scan its already-validated
// tokens as well, so uploaded metadata and embedded manifests share one meaning.
function parseJson(data, label) {
  requireValid(Buffer.byteLength(data) <= MAX_JSON_BYTES, `${label} exceeds ${MAX_JSON_BYTES} bytes`);
  try {
    const source = typeof data === 'string' ? data : new TextDecoder('utf-8', { fatal: true }).decode(data);
    const result = JSON.parse(source);
    const tokens = source.match(/"(?:[^"\\]|\\.)*"|[{}\[\]:,]|[^\s{}\[\]:,]+/g) || [];
    const stack = [];
    for (let i = 0; i < tokens.length; i += 1) {
      const token = tokens[i];
      if (token === '{' || token === '[') stack.push(token === '{' ? new Set() : null);
      else if (token === '}' || token === ']') stack.pop();
      else if (token.startsWith('"') && tokens[i + 1] === ':') {
        const keys = stack[stack.length - 1];
        const key = JSON.parse(token);
        requireValid(!keys.has(key), `Duplicate JSON key: ${key}`);
        keys.add(key);
      }
    }
    return result;
  } catch (error) {
    throw httpError(400, `Invalid ${label}: ${error.message}`);
  }
}

async function inspectZip(buffer, { label, maxBytes, maxExpandedBytes, capture }) {
  requireValid(Buffer.isBuffer(buffer) && buffer.length > 0 && buffer.length <= maxBytes, `${label} must be 1..${maxBytes} bytes`);
  let zip;
  try {
    zip = await yauzl.fromBufferPromise(buffer, { lazyEntries: true, strictFileNames: true });
    requireValid(zip.entryCount > 0 && zip.entryCount <= MAX_ENTRIES, `Too many or no ${label} entries`);
    const names = new Set();
    const captured = new Map();
    let expanded = 0;
    let declared = 0;
    for await (const entry of zip.eachEntry()) {
      const name = entry.fileName;
      requireValid(!names.has(name), `Duplicate ${label} entry: ${name}`);
      names.add(name);
      requireValid(!name.startsWith('/') && !/[\\:]/.test(name) && !name.split('/').includes('..'), `Unsafe ${label} entry path`);
      requireValid(!(entry.generalPurposeBitFlag & 1), `Encrypted ${label} entries are unsupported`);
      requireValid(((entry.externalFileAttributes >>> 16) & 0o170000) !== 0o120000, `Symlinks in ${label} are unsupported`);
      const fileType = (entry.externalFileAttributes >>> 16) & 0o170000;
      requireValid(fileType === 0 || fileType === 0o100000 || fileType === 0o040000,
        `Non-regular ${label} entry is unsupported: ${name}`);
      requireValid(!name.endsWith('/') || fileType === 0 || fileType === 0o040000,
        `Invalid directory entry in ${label}: ${name}`);
      requireValid(fileType !== 0o040000 || name.endsWith('/'),
        `Directory entry must end with a slash in ${label}: ${name}`);
      declared += entry.uncompressedSize;
      requireValid(declared <= maxExpandedBytes, `${label} expands beyond limit`);
      const limit = capture(name);
      if (limit !== null) requireValid(entry.uncompressedSize <= limit, `${label} entry exceeds limit: ${name}`);
      const stream = await zip.openReadStreamPromise(entry);
      const chunks = [];
      let checksum = 0;
      let size = 0;
      for await (const chunk of stream) {
        expanded += chunk.length;
        size += chunk.length;
        requireValid(expanded <= maxExpandedBytes, `${label} expands beyond limit`);
        if (limit !== null) {
          requireValid(size <= limit, `${label} entry exceeds limit: ${name}`);
          chunks.push(chunk);
        }
        checksum = crc32.buf(chunk, checksum);
      }
      requireValid(size === entry.uncompressedSize && (checksum >>> 0) === entry.crc32, `Invalid ${label} entry size/CRC: ${name}`);
      if (limit !== null) {
        captured.set(name, Buffer.concat(chunks));
      } else if (label === 'source ZIP' && !name.endsWith('/')) {
        captured.set(name, Buffer.alloc(0));
      }
    }
    return { names, captured };
  } catch (error) {
    throw httpError(400, `Invalid ${label}: ${error.message}`);
  } finally {
    if (zip) zip.close();
  }
}

async function validateJar(buffer, metadata) {
  const { names, captured } = await inspectZip(buffer, {
    label: 'JAR', maxBytes: MAX_JAR_BYTES, maxExpandedBytes: MAX_JAR_EXPANDED_BYTES,
    capture: (name) => ['ModTheSpire.json', 'agent-patch.json'].includes(name) ? MAX_JSON_BYTES : null
  });
  requireValid(captured.has('ModTheSpire.json'), 'JAR lacks ModTheSpire.json');
  const manifest = parseJson(captured.get('ModTheSpire.json'), 'ModTheSpire.json');
  requireValid(manifest && typeof manifest === 'object' && !Array.isArray(manifest), 'ModTheSpire.json must be an object');
  text(manifest.modid, 'JAR modid');
  requireValid(manifest.modid !== metadata.target.modId, 'Patch modid must differ from target modId');
  for (const key of ['name', 'description', 'version']) {
    requireValid(manifest[key] === metadata[key], `JAR ${key} does not match metadata`);
  }
  requireValid(Array.isArray(manifest.dependencies) && manifest.dependencies.includes(metadata.target.modId), 'JAR dependencies must include exact case-sensitive target modId');
  if (captured.has('agent-patch.json')) {
    const descriptor = parseJson(captured.get('agent-patch.json'), 'agent-patch.json');
    const expected = { parent_mod_id: metadata.target.modId, patch_mod_id: manifest.modid,
      name: metadata.name, version: metadata.version, description: metadata.description };
    requireValid(descriptor && Object.entries(expected).every(([key, value]) => descriptor[key] === value), 'agent-patch.json mismatch');
    text(descriptor.patch_id, 'patch_id');
  }
  requireValid([...names].some((name) => name.endsWith('.class')), 'JAR contains no compiled classes');
  return { size: buffer.length, sha256: createHash('sha256').update(buffer).digest('hex'), patchModId: manifest.modid };
}

// Source archives contain paths relative to src/. Never extract them to disk.
async function readSourceArchive(buffer) {
  const { names, captured } = await inspectZip(buffer, {
    label: 'source ZIP', maxBytes: MAX_SOURCE_BYTES, maxExpandedBytes: MAX_SOURCE_EXPANDED_BYTES,
    capture: (name) => name.endsWith('/') ? null : MAX_SOURCE_EXPANDED_BYTES
  });
  requireValid(captured.size > 0, 'Source ZIP contains no files');
  const { caseFold } = await import('unicode-case-folding');
  requireValid(captured.size <= 256, 'Source ZIP contains more than 256 files');
  const folded = new Set();
  const canonicalPrefixes = new Map();
  for (const name of captured.keys()) {
    const parts = name.split('/');
    parts.forEach((part) => pathSegment(part, 'source path', 240));
    requireValid(Buffer.byteLength(name) <= 1024, 'Source path exceeds limit');
    for (let i = 0; i < parts.length; i += 1) {
      const actual = parts.slice(0, i + 1).join('/');
      const identity = caseFold(actual);
      const canonical = canonicalPrefixes.get(identity);
      requireValid(!canonical || canonical === actual, 'Case-insensitive source path collision');
      canonicalPrefixes.set(identity, actual);
    }
    const identity = caseFold(name);
    requireValid(!folded.has(identity), 'Case-insensitive source path collision');
    folded.add(identity);
  }
  for (const name of folded) {
    const parts = name.split('/');
    for (let i = 1; i < parts.length; i += 1) {
      requireValid(!folded.has(parts.slice(0, i).join('/')), 'Source file/directory collision');
    }
  }
  for (const name of names) {
    if (name.endsWith('/')) {
      const directory = name.slice(0, -1);
      const actual = canonicalPrefixes.get(caseFold(directory));
      requireValid(!actual || actual === directory, 'Case-insensitive source path collision');
      requireValid(!captured.has(directory), 'Source file/directory collision');
    }
  }
  return captured;
}

module.exports = {
  MAX_JSON_BYTES, MAX_JAR_BYTES, MAX_SOURCE_BYTES,
  requireValid, text, pathSegment, patchDirectory, parseJson, validateMetadata, validateJar, readSourceArchive
};
