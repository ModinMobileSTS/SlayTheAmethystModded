'use strict';

const { createSign } = require('node:crypto');

const { APP_NAME } = require('./constants');
const {
  safeJsonParse,
  firstNonEmpty,
  base64UrlEncodeJson,
  base64UrlEncodeBuffer,
  normalizeSlug,
  encodePathSegment,
  httpError,
  summarizeErrorWithCause
} = require('./utils');

async function ensureDiagnosticsRelease(target, currentConfig) {
  const existing = await findReleaseByTag(target, currentConfig);
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

async function findReleaseByTag(target, currentConfig) {
  return githubFetchJsonAllowNotFound(
    `/repos/${encodePathSegment(target.owner)}/${encodePathSegment(target.repo)}/releases/tags/${encodePathSegment(target.tag)}`,
    {
      method: 'GET'
    },
    currentConfig
  );
}

async function findReleaseAssetByName(releaseRecord, assetName, target, currentConfig) {
  const assets = await listReleaseAssets(releaseRecord, target, currentConfig);
  return assets.find((asset) => asset && asset.name === assetName) || null;
}

async function listReleaseAssets(releaseRecord, target, currentConfig) {
  if (Array.isArray(releaseRecord && releaseRecord.assets)) {
    return releaseRecord.assets;
  }
  if (!releaseRecord || !releaseRecord.id) {
    return [];
  }
  return githubFetchJson(
    `/repos/${encodePathSegment(target.owner)}/${encodePathSegment(target.repo)}/releases/${encodePathSegment(releaseRecord.id)}/assets`,
    {
      method: 'GET'
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
  const requestUrl = `${uploadBaseUrl}?name=${encodeURIComponent(target.assetName)}`;

  const response = await fetchWithGithubContext(
    requestUrl,
    {
      method: 'POST',
      headers: {
        Accept: 'application/vnd.github+json',
        Authorization: authorization,
        'Content-Type': target.contentType,
        'Content-Length': String(target.content.length),
        'User-Agent': APP_NAME,
        'X-GitHub-Api-Version': '2022-11-28'
      },
      body: target.content
    },
    `GitHub asset upload request failed for ${target.owner}/${target.repo}`
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

async function deleteReleaseAsset(target, currentConfig) {
  const authorization = await getGithubInstallationAuthorization(currentConfig);
  const requestUrl =
    `https://api.github.com/repos/${encodePathSegment(target.owner)}/${encodePathSegment(target.repo)}` +
    `/releases/assets/${encodePathSegment(target.assetId)}`;
  const response = await fetchWithGithubContext(
    requestUrl,
    {
      method: 'DELETE',
      headers: {
        Accept: 'application/vnd.github+json',
        Authorization: authorization,
        'User-Agent': APP_NAME,
        'X-GitHub-Api-Version': '2022-11-28'
      }
    },
    `GitHub asset delete request failed for ${target.owner}/${target.repo}`
  );

  if (response.status === 404) {
    return;
  }
  if (!response.ok) {
    const rawText = await response.text();
    throw httpError(502, rawText || `GitHub asset delete failed (${response.status})`);
  }
}

async function downloadReleaseAssetContent(asset, currentConfig) {
  const authorization = await getGithubInstallationAuthorization(currentConfig);
  const response = await fetchWithGithubContext(
    asset.url,
    {
      method: 'GET',
      headers: {
        Accept: 'application/octet-stream',
        Authorization: authorization,
        'User-Agent': APP_NAME,
        'X-GitHub-Api-Version': '2022-11-28'
      }
    },
    'GitHub asset download request failed'
  );

  const content = Buffer.from(await response.arrayBuffer());
  if (!response.ok) {
    throw httpError(502, content.toString('utf8') || `GitHub asset download failed (${response.status})`);
  }
  return content;
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

async function createGithubIssueComment(issueNumber, commentBody, currentConfig) {
  return githubFetchJson(
    `/repos/${encodePathSegment(currentConfig.githubOwner)}/${encodePathSegment(currentConfig.githubRepo)}/issues/${encodePathSegment(issueNumber)}/comments`,
    {
      method: 'POST',
      body: JSON.stringify({
        body: commentBody
      })
    },
    currentConfig
  );
}

async function updateGithubIssueState(issueNumber, targetState, currentConfig) {
  return githubFetchJson(
    `/repos/${encodePathSegment(currentConfig.githubOwner)}/${encodePathSegment(currentConfig.githubRepo)}/issues/${encodePathSegment(issueNumber)}`,
    {
      method: 'PATCH',
      body: JSON.stringify({
        state: targetState
      })
    },
    currentConfig
  );
}

async function githubFetchJson(path, init, currentConfig) {
  return githubFetchJsonInternal(path, init, currentConfig, false);
}

async function githubFetchJsonAllowNotFound(path, init, currentConfig) {
  return githubFetchJsonInternal(path, init, currentConfig, true);
}

async function githubFetchJsonInternal(path, init, currentConfig, allowNotFound) {
  const authorization = await getGithubInstallationAuthorization(currentConfig);
  const requestUrl = `https://api.github.com${path}`;
  const response = await fetchWithGithubContext(
    requestUrl,
    {
      method: init.method,
      headers: {
        Accept: 'application/vnd.github+json',
        Authorization: authorization,
        'Content-Type': 'application/json',
        'User-Agent': APP_NAME,
        'X-GitHub-Api-Version': '2022-11-28'
      },
      body: init.body
    },
    `GitHub API request failed for ${path}`
  );

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
  const requestUrl = `https://api.github.com${path}`;
  const response = await fetchWithGithubContext(
    requestUrl,
    {
      method: init.method,
      headers: {
        Accept: 'application/vnd.github+json',
        Authorization: authorization,
        'Content-Type': 'application/json',
        'User-Agent': APP_NAME,
        'X-GitHub-Api-Version': '2022-11-28'
      },
      body: init.body
    },
    `GitHub API request failed for ${path}`
  );

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

async function fetchWithGithubContext(url, init, failureLabel) {
  try {
    return await fetch(url, init);
  } catch (error) {
    const detail = summarizeErrorWithCause(error);
    throw httpError(502, `${failureLabel}: ${detail || 'fetch failed'}`);
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

function buildNotificationStateReleaseDescriptor(dateLike, currentConfig) {
  const date = new Date(dateLike);
  if (Number.isNaN(date.getTime())) {
    throw httpError(400, 'Invalid issue created_at for notification state lookup');
  }

  const year = String(date.getUTCFullYear());
  const month = String(date.getUTCMonth() + 1).padStart(2, '0');
  const monthStamp = `${year}-${month}`;

  return {
    owner: currentConfig.notificationStateOwner,
    repo: currentConfig.notificationStateRepo,
    tag: `${currentConfig.notificationStateReleasePrefix}-${monthStamp}`,
    name: `Feedback mail state ${monthStamp}`,
    body: [
      'Automated feedback mail notification state.',
      '',
      `Month: ${monthStamp}`,
      `Target branch: ${currentConfig.notificationStateBranch}`
    ].join('\n'),
    targetCommitish: currentConfig.notificationStateBranch
  };
}

function buildNotificationStateAssetName(owner, repo, issueNumber) {
  return `${normalizeSlug(owner)}--${normalizeSlug(repo)}--issue-${Number(issueNumber)}.json`;
}

module.exports = {
  ensureDiagnosticsRelease,
  findReleaseByTag,
  findReleaseAssetByName,
  listReleaseAssets,
  uploadReleaseAsset,
  deleteReleaseAsset,
  downloadReleaseAssetContent,
  createGithubIssue,
  createGithubIssueComment,
  updateGithubIssueState,
  githubFetchJson,
  githubFetchJsonAllowNotFound,
  getGithubInstallationAuthorization,
  createGithubAppJwt,
  buildGithubReleaseAssetUrl,
  buildBundleBaseName,
  buildDiagnosticsReleaseDescriptor,
  buildNotificationStateReleaseDescriptor,
  buildNotificationStateAssetName
};
