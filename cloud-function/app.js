'use strict';

const express = require('express');
const multer = require('multer');
const { createSign, randomUUID } = require('node:crypto');

const APP_NAME = 'sts-feedback-relay';
const DEFAULT_PORT = 9000;
const DEFAULT_BUNDLE_MAX_BYTES = 25 * 1024 * 1024;

const config = loadConfig();
const upload = multer({
  storage: multer.memoryStorage(),
  limits: {
    fileSize: config.bundleMaxBytes
  }
});

const app = express();

app.disable('x-powered-by');
app.use(express.json({ limit: '1mb' }));

app.get('/', (_req, res) => {
  res.json({
    ok: true,
    service: APP_NAME,
    now: new Date().toISOString(),
    endpoint: '/api/sts-feedback'
  });
});

app.get('/healthz', (_req, res) => {
  res.json({
    ok: true,
    service: APP_NAME
  });
});

const handleFeedbackSubmission = async (req, res, next) => {
  try {
    enforceSharedSecret(req, config);

    const submission = parseSubmissionRequest(req);
    const bundleRecord = await maybeUploadBundle(submission, req.file, config);
    const labels = buildIssueLabels(submission.payload, config.staticLabels);
    const issueBody = appendRelaySection(
      prepareIssueBody(submission.issueBody, submission.payload),
      submission.requestId,
      bundleRecord
    );
    const issue = await createGithubIssue({
      title: submission.issueTitle,
      body: issueBody,
      labels
    }, config);

    res.status(201).json({
      ok: true,
      requestId: submission.requestId,
      issueNumber: issue.number,
      issueUrl: issue.html_url,
      issue: {
        number: issue.number,
        html_url: issue.html_url
      },
      diagnosticBundle: bundleRecord
    });
  } catch (error) {
    next(error);
  }
};

app.post('/', upload.single('bundle'), handleFeedbackSubmission);
app.post('/api/sts-feedback', upload.single('bundle'), handleFeedbackSubmission);

app.use((error, _req, res, _next) => {
  if (error && error.code === 'LIMIT_FILE_SIZE') {
    res.status(413).json({
      ok: false,
      error: 'bundle_too_large',
      message: `Uploaded bundle exceeds ${config.bundleMaxBytes} bytes.`
    });
    return;
  }

  const status = normalizeStatusCode(error);
  res.status(status).json({
    ok: false,
    error: normalizeErrorCode(error),
    message: normalizeErrorMessage(error)
  });
});

if (require.main === module) {
  app.listen(config.port, () => {
    console.log(`${APP_NAME} listening on http://localhost:${config.port}`);
  });
}

module.exports = app;

function loadConfig() {
  const githubOwner = requireEnv('GITHUB_OWNER');
  const githubRepo = requireEnv('GITHUB_REPO');

  return {
    port: parsePositiveInteger(process.env.PORT, DEFAULT_PORT),
    bundleMaxBytes: parsePositiveInteger(process.env.BUNDLE_MAX_BYTES, DEFAULT_BUNDLE_MAX_BYTES),
    sharedSecret: readOptionalEnv('FEEDBACK_SHARED_SECRET'),
    githubAuth: loadGithubAuthConfig(),
    githubOwner,
    githubRepo,
    staticLabels: parseCsv(process.env.GITHUB_ISSUE_LABELS, ['feedback', 'client:android']),
    diagnosticsOwner: readOptionalEnv('GITHUB_DIAGNOSTICS_OWNER') || githubOwner,
    diagnosticsRepo: readOptionalEnv('GITHUB_DIAGNOSTICS_REPO') || 'SlayTheDiagnostics',
    diagnosticsBranch: readOptionalEnv('GITHUB_DIAGNOSTICS_BRANCH') || 'main',
    diagnosticsReleasePrefix: normalizeReleasePrefix(
      readOptionalEnv('GITHUB_DIAGNOSTICS_RELEASE_PREFIX') || 'feedback'
    )
  };
}

function loadGithubAuthConfig() {
  const githubAppId = readOptionalEnv('GITHUB_APP_ID');
  const githubAppInstallationId = readOptionalEnv('GITHUB_APP_INSTALLATION_ID');
  const githubAppPrivateKey = normalizePrivateKey(readOptionalEnv('GITHUB_APP_PRIVATE_KEY'));

  if (githubAppId || githubAppInstallationId || githubAppPrivateKey) {
    if (!githubAppId) {
      throw new Error('Missing required environment variable: GITHUB_APP_ID');
    }
    if (!githubAppInstallationId) {
      throw new Error('Missing required environment variable: GITHUB_APP_INSTALLATION_ID');
    }
    if (!githubAppPrivateKey) {
      throw new Error('Missing required environment variable: GITHUB_APP_PRIVATE_KEY');
    }
    return {
      mode: 'app',
      appId: githubAppId,
      installationId: githubAppInstallationId,
      privateKey: githubAppPrivateKey,
      cachedInstallationToken: null
    };
  }

  return {
    mode: 'token',
    token: requireEnv('GITHUB_TOKEN')
  };
}

function parseSubmissionRequest(req) {
  const payload = parsePayloadJson(req.body.payload_json);
  const requestId = randomUUID();
  const issueTitle = firstNonEmpty(req.body.issue_title, payload && payload.issue && payload.issue.title);
  const issueBody = firstNonEmpty(req.body.issue_body, payload && payload.issue && payload.issue.body);

  if (!issueTitle) {
    throw httpError(400, 'Missing issue_title');
  }
  if (!issueBody) {
    throw httpError(400, 'Missing issue_body');
  }

  return {
    requestId,
    payload,
    issueTitle,
    issueBody
  };
}

function parsePayloadJson(rawValue) {
  const normalized = typeof rawValue === 'string' ? rawValue.trim() : '';
  if (!normalized) {
    return {};
  }
  try {
    return JSON.parse(normalized);
  } catch (_error) {
    throw httpError(400, 'payload_json is not valid JSON');
  }
}

function enforceSharedSecret(req, currentConfig) {
  if (!currentConfig.sharedSecret) {
    return;
  }
  const provided = String(req.get('x-feedback-key') || '').trim();
  if (!provided || provided !== currentConfig.sharedSecret) {
    throw httpError(401, 'Invalid X-Feedback-Key');
  }
}

async function maybeUploadBundle(submission, file, currentConfig) {
  if (!file) {
    return null;
  }

  const now = new Date();
  const baseName = buildBundleBaseName(submission.requestId, file.originalname);
  const bundleAssetName = `${baseName}.zip`;
  const metadataAssetName = `${baseName}.json`;
  const repository = `${currentConfig.diagnosticsOwner}/${currentConfig.diagnosticsRepo}`;
  const release = buildDiagnosticsReleaseDescriptor(now, currentConfig);

  const metadata = {
    requestId: submission.requestId,
    receivedAt: new Date().toISOString(),
    issueTitle: submission.issueTitle,
    fileName: file.originalname || 'feedback-bundle.zip',
    mimeType: file.mimetype || 'application/octet-stream',
    sizeBytes: file.size,
    payload: submission.payload
  };

  try {
    const releaseRecord = await ensureDiagnosticsRelease(release, currentConfig);

    const bundleUpload = await uploadReleaseAsset({
      owner: currentConfig.diagnosticsOwner,
      repo: currentConfig.diagnosticsRepo,
      uploadUrl: releaseRecord.upload_url,
      assetName: bundleAssetName,
      contentType: file.mimetype || 'application/zip',
      content: file.buffer
    }, currentConfig);

    const metadataUpload = await uploadReleaseAsset({
      owner: currentConfig.diagnosticsOwner,
      repo: currentConfig.diagnosticsRepo,
      uploadUrl: releaseRecord.upload_url,
      assetName: metadataAssetName,
      contentType: 'application/json; charset=utf-8',
      content: Buffer.from(JSON.stringify(metadata, null, 2), 'utf8')
    }, currentConfig);

    return {
      stored: true,
      repository,
      releaseTag: releaseRecord.tag_name,
      releaseName: releaseRecord.name || release.name,
      fileName: file.originalname || `${baseName}.zip`,
      sizeBytes: file.size,
      bundleAssetName,
      bundleUrl: bundleUpload.htmlUrl,
      metadataAssetName,
      metadataUrl: metadataUpload.htmlUrl
    };
  } catch (error) {
    return {
      stored: false,
      repository,
      releaseTag: release.tag,
      fileName: file.originalname || 'feedback-bundle.zip',
      sizeBytes: file.size,
      uploadError: summarizeInlineErrorMessage(error)
    };
  }
}

async function ensureDiagnosticsRelease(target, currentConfig) {
  const existing = await githubFetchJsonAllowNotFound(
    `/repos/${encodePathSegment(target.owner)}/${encodePathSegment(target.repo)}/releases/tags/${encodePathSegment(target.tag)}`,
    {
      method: 'GET'
    },
    currentConfig
  );
  if (existing) {
    return existing;
  }

  return githubFetchJson(
    `/repos/${encodePathSegment(target.owner)}/${encodePathSegment(target.repo)}/releases`,
    {
      method: 'POST',
      body: JSON.stringify({
        tag_name: target.tag,
        name: target.name,
        body: target.body,
        target_commitish: target.targetCommitish,
        draft: false,
        prerelease: false,
        generate_release_notes: false
      })
    },
    currentConfig
  );
}

async function uploadReleaseAsset(target, currentConfig) {
  const uploadBaseUrl = String(target.uploadUrl || '').replace(/\{.*$/, '');
  if (!uploadBaseUrl) {
    throw httpError(502, 'Missing release upload URL');
  }

  const authorization = await getGithubInstallationAuthorization(currentConfig);

  const response = await fetch(
    `${uploadBaseUrl}?name=${encodeURIComponent(target.assetName)}`,
    {
      method: 'POST',
      headers: {
        'Accept': 'application/vnd.github+json',
        'Authorization': authorization,
        'Content-Type': target.contentType,
        'Content-Length': String(target.content.length),
        'User-Agent': APP_NAME,
        'X-GitHub-Api-Version': '2022-11-28'
      },
      body: target.content
    }
  );

  const rawText = await response.text();
  const data = rawText ? safeJsonParse(rawText) : null;
  if (!response.ok) {
    const message = data && data.message ? data.message : rawText || `GitHub upload failed (${response.status})`;
    throw httpError(502, message);
  }

  const asset = data || {};
  return {
    htmlUrl: asset.browser_download_url || buildGithubReleaseAssetUrl(target.owner, target.repo, target.assetName),
    assetId: asset.id || null,
    assetName: asset.name || target.assetName
  };
}

async function createGithubIssue(issueRequest, currentConfig) {
  return githubFetchJson(
    `/repos/${encodePathSegment(currentConfig.githubOwner)}/${encodePathSegment(currentConfig.githubRepo)}/issues`,
    {
      method: 'POST',
      body: JSON.stringify(issueRequest)
    },
    currentConfig
  );
}

function buildIssueLabels(payload, staticLabels) {
  const labels = new Set(staticLabels);
  const feedback = payload && payload.feedback ? payload.feedback : {};
  const category = normalizeSlug(feedback.category || feedback.categoryLabel);
  const issueType = normalizeSlug(feedback.gameIssueType || feedback.gameIssueTypeLabel);

  if (category) {
    labels.add(`feedback:${category}`);
  }
  if (issueType) {
    labels.add(`feedback:${issueType}`);
  }

  return Array.from(labels).filter(Boolean);
}

function prepareIssueBody(issueBody, payload) {
  const feedback = payload && payload.feedback ? payload.feedback : {};
  if (!isFeatureRequest(feedback)) {
    return String(issueBody || '').trim();
  }

  return removeMarkdownSections(issueBody, [
    '环境信息',
    '启用模组快照',
    'latest.log 关键行'
  ]);
}

function appendRelaySection(issueBody, requestId, bundleRecord) {
  const lines = [
    issueBody.trim(),
    '',
    '## 云函数记录',
    `- Request ID: ${requestId}`
  ];

  if (!bundleRecord) {
    lines.push('- Diagnostic bundle: not provided');
  } else if (!bundleRecord.stored) {
    const location = bundleRecord.repository
      ? ` to ${bundleRecord.repository} release ${bundleRecord.releaseTag || 'unknown'}`
      : '';
    const reason = bundleRecord.uploadError
      ? `, upload failed: ${bundleRecord.uploadError}`
      : '';
    lines.push(`- Diagnostic bundle: received but not persisted by relay${location} (${bundleRecord.fileName}, ${bundleRecord.sizeBytes} bytes${reason})`);
  } else {
    lines.push(`- Diagnostic bundle: [${bundleRecord.fileName}](${bundleRecord.bundleUrl})`);
    lines.push(`- Diagnostic metadata: [${bundleRecord.metadataAssetName}](${bundleRecord.metadataUrl})`);
    lines.push(`- Diagnostic release: ${bundleRecord.repository}@${bundleRecord.releaseTag}`);
  }

  return lines.join('\n');
}

async function githubFetchJson(path, init, currentConfig) {
  return githubFetchJsonInternal(path, init, currentConfig, false);
}

async function githubFetchJsonAllowNotFound(path, init, currentConfig) {
  return githubFetchJsonInternal(path, init, currentConfig, true);
}

async function githubFetchJsonInternal(path, init, currentConfig, allowNotFound) {
  const authorization = await getGithubInstallationAuthorization(currentConfig);
  const response = await fetch(`https://api.github.com${path}`, {
    method: init.method,
    headers: {
      'Accept': 'application/vnd.github+json',
      'Authorization': authorization,
      'Content-Type': 'application/json',
      'User-Agent': APP_NAME,
      'X-GitHub-Api-Version': '2022-11-28'
    },
    body: init.body
  });

  const rawText = await response.text();
  const data = rawText ? safeJsonParse(rawText) : null;
  if (allowNotFound && response.status === 404) {
    return null;
  }
  if (!response.ok) {
    const message = data && data.message ? data.message : rawText || `GitHub API request failed (${response.status})`;
    throw httpError(502, message);
  }
  return data;
}

async function getGithubInstallationAuthorization(currentConfig) {
  const auth = currentConfig.githubAuth;
  if (auth.mode === 'token') {
    return `Bearer ${auth.token}`;
  }

  const installationToken = await getGithubInstallationToken(currentConfig);
  return `Bearer ${installationToken}`;
}

async function getGithubInstallationToken(currentConfig) {
  const auth = currentConfig.githubAuth;
  if (auth.mode !== 'app') {
    return auth.token;
  }

  const now = Date.now();
  const cached = auth.cachedInstallationToken;
  if (cached && cached.expiresAtMs - 60_000 > now) {
    return cached.token;
  }

  const jwt = createGithubAppJwt(auth.appId, auth.privateKey);
  const tokenResponse = await githubFetchJsonWithAuthorization(
    `/app/installations/${encodePathSegment(auth.installationId)}/access_tokens`,
    {
      method: 'POST'
    },
    `Bearer ${jwt}`
  );

  const token = firstNonEmpty(tokenResponse && tokenResponse.token);
  const expiresAt = Date.parse(tokenResponse && tokenResponse.expires_at);
  if (!token || !Number.isFinite(expiresAt)) {
    throw httpError(502, 'GitHub App installation token response was incomplete');
  }

  auth.cachedInstallationToken = {
    token,
    expiresAtMs: expiresAt
  };
  return token;
}

async function githubFetchJsonWithAuthorization(path, init, authorization) {
  const response = await fetch(`https://api.github.com${path}`, {
    method: init.method,
    headers: {
      'Accept': 'application/vnd.github+json',
      'Authorization': authorization,
      'Content-Type': 'application/json',
      'User-Agent': APP_NAME,
      'X-GitHub-Api-Version': '2022-11-28'
    },
    body: init.body
  });

  const rawText = await response.text();
  const data = rawText ? safeJsonParse(rawText) : null;
  if (!response.ok) {
    const message = data && data.message ? data.message : rawText || `GitHub API request failed (${response.status})`;
    throw httpError(502, message);
  }
  return data;
}

function createGithubAppJwt(appId, privateKey) {
  const now = Math.floor(Date.now() / 1000);
  const header = {
    alg: 'RS256',
    typ: 'JWT'
  };
  const payload = {
    iat: now - 60,
    exp: now + 9 * 60,
    iss: String(appId)
  };
  const signingInput = `${base64UrlEncodeJson(header)}.${base64UrlEncodeJson(payload)}`;
  const signer = createSign('RSA-SHA256');
  signer.update(signingInput);
  signer.end();
  const signature = signer.sign(privateKey);
  return `${signingInput}.${base64UrlEncodeBuffer(signature)}`;
}

function safeJsonParse(rawText) {
  try {
    return JSON.parse(rawText);
  } catch (_error) {
    return { raw: rawText };
  }
}

function buildGithubReleaseAssetUrl(owner, repo, assetName) {
  return `https://github.com/${owner}/${repo}/releases?q=${encodeURIComponent(assetName)}`;
}

function buildBundleBaseName(requestId, originalName) {
  const original = String(originalName || 'feedback-bundle.zip').trim();
  const nameWithoutZip = original.toLowerCase().endsWith('.zip') ? original.slice(0, -4) : original;
  const sanitized = nameWithoutZip.replace(/[^a-zA-Z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || 'feedback-bundle';
  return `${Date.now()}-${requestId}-${sanitized}`;
}

function buildDiagnosticsReleaseDescriptor(date, currentConfig) {
  const year = String(date.getUTCFullYear());
  const month = String(date.getUTCMonth() + 1).padStart(2, '0');
  const day = String(date.getUTCDate()).padStart(2, '0');
  const dayStamp = `${year}-${month}-${day}`;

  return {
    owner: currentConfig.diagnosticsOwner,
    repo: currentConfig.diagnosticsRepo,
    tag: `${currentConfig.diagnosticsReleasePrefix}-${dayStamp}`,
    name: `Feedback artifacts ${dayStamp}`,
    body: [
      'Automated feedback artifact bucket.',
      '',
      `Date: ${dayStamp}`,
      `Target branch: ${currentConfig.diagnosticsBranch}`
    ].join('\n'),
    targetCommitish: currentConfig.diagnosticsBranch
  };
}

function parsePositiveInteger(rawValue, fallbackValue) {
  const parsed = Number.parseInt(String(rawValue || '').trim(), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallbackValue;
}

function parseCsv(rawValue, fallbackValue) {
  const parts = String(rawValue || '')
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
  return parts.length > 0 ? parts : fallbackValue;
}

function normalizeReleasePrefix(value) {
  const normalized = String(value || '')
    .trim()
    .replace(/[^a-zA-Z0-9._-]+/g, '-')
    .replace(/^-+|-+$/g, '');
  return normalized || 'feedback';
}

function normalizePrivateKey(value) {
  return String(value || '')
    .trim()
    .replace(/\\n/g, '\n');
}

function base64UrlEncodeJson(value) {
  return base64UrlEncodeBuffer(Buffer.from(JSON.stringify(value), 'utf8'));
}

function base64UrlEncodeBuffer(value) {
  return Buffer.from(value)
    .toString('base64')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/g, '');
}

function isFeatureRequest(feedback) {
  return normalizeSlug(feedback && (feedback.category || feedback.categoryLabel)) === 'feature-request';
}

function removeMarkdownSections(markdown, sectionTitles) {
  const titles = new Set(sectionTitles.map((title) => normalizeHeadingTitle(title)));
  const lines = String(markdown || '').replace(/\r\n/g, '\n').split('\n');
  const keptLines = [];
  let skipping = false;

  for (const line of lines) {
    const heading = parseLevelTwoHeading(line);
    if (heading) {
      const normalizedHeading = normalizeHeadingTitle(heading);
      if (titles.has(normalizedHeading)) {
        skipping = true;
        continue;
      }
      if (skipping) {
        skipping = false;
      }
    }

    if (!skipping) {
      keptLines.push(line);
    }
  }

  return keptLines
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

function parseLevelTwoHeading(line) {
  const match = /^##\s+(.+?)\s*$/.exec(String(line || ''));
  return match ? match[1] : '';
}

function normalizeHeadingTitle(title) {
  return String(title || '')
    .trim()
    .toLowerCase();
}

function firstNonEmpty() {
  for (const value of arguments) {
    const normalized = String(value || '').trim();
    if (normalized) {
      return normalized;
    }
  }
  return '';
}

function normalizeSlug(value) {
  return String(value || '')
    .trim()
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
}

function encodePath(path) {
  return String(path)
    .split('/')
    .map(encodePathSegment)
    .join('/');
}

function encodePathSegment(value) {
  return encodeURIComponent(String(value));
}

function readOptionalEnv(name) {
  const value = process.env[name];
  return typeof value === 'string' ? value.trim() : '';
}

function requireEnv(name) {
  const value = readOptionalEnv(name);
  if (!value) {
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

function httpError(statusCode, message) {
  const error = new Error(message);
  error.statusCode = statusCode;
  return error;
}

function normalizeStatusCode(error) {
  return error && Number.isInteger(error.statusCode) ? error.statusCode : 500;
}

function normalizeErrorCode(error) {
  if (error && error.code) {
    return String(error.code);
  }
  const statusCode = normalizeStatusCode(error);
  return statusCode >= 500 ? 'internal_error' : 'bad_request';
}

function normalizeErrorMessage(error) {
  if (error && error.message) {
    return String(error.message);
  }
  return 'Unexpected error';
}

function summarizeInlineErrorMessage(error) {
  return normalizeErrorMessage(error)
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 180);
}
