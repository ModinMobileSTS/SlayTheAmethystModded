'use strict';

const { randomUUID } = require('node:crypto');

const {
  githubFetchJson,
  ensureDiagnosticsRelease,
  uploadReleaseAsset,
  buildDiagnosticsReleaseDescriptor,
  buildBundleBaseName
} = require('./github');
const {
  firstNonEmpty,
  httpError,
  buildFeedbackProxyMarker,
  parsePositiveInteger,
  encodePathSegment
} = require('./utils');

const DEFAULT_ISSUE_BROWSE_PAGE_SIZE = 20;
const MAX_ISSUE_BROWSE_PAGE_SIZE = 50;

function parseIssueMessageRequest(req) {
  const issueNumber = Number.parseInt(String(req.body.issue_number || '').trim(), 10);
  if (!Number.isFinite(issueNumber) || issueNumber <= 0) {
    throw httpError(400, 'Invalid issue_number');
  }

  const messageText = String(req.body.message_text || '').trim();
  const files = Array.isArray(req.files) ? req.files : [];
  if (!messageText && files.length === 0) {
    throw httpError(400, 'message_text or screenshots is required');
  }

  return {
    requestId: randomUUID(),
    issueNumber,
    messageText,
    playerName: firstNonEmpty(req.body.player_name, 'player'),
    appVersion: firstNonEmpty(req.body.app_version, 'unknown'),
    deviceLabel: firstNonEmpty(req.body.device_label, 'Android Device'),
    screenshots: files
  };
}

function parseIssueStateRequest(req) {
  const issueNumber = Number.parseInt(String(req.body && req.body.issue_number || '').trim(), 10);
  if (!Number.isFinite(issueNumber) || issueNumber <= 0) {
    throw httpError(400, 'Invalid issue_number');
  }

  const targetState = String(req.body && req.body.target_state || '').trim().toLowerCase();
  if (targetState !== 'open' && targetState !== 'closed') {
    throw httpError(400, 'target_state must be open or closed');
  }

  return {
    issueNumber,
    targetState
  };
}

function parseIssueBrowseRequest(req) {
  const page = parsePositiveInteger(req.query && req.query.page, 1);
  const pageSize = Math.min(
    parsePositiveInteger(req.query && req.query.per_page, DEFAULT_ISSUE_BROWSE_PAGE_SIZE),
    MAX_ISSUE_BROWSE_PAGE_SIZE
  );

  return {
    page,
    pageSize
  };
}

async function listBrowsableIssues(request, currentConfig) {
  const response = await githubFetchJson(
    `/repos/${encodePathSegment(currentConfig.githubOwner)}/${encodePathSegment(currentConfig.githubRepo)}/issues` +
      `?state=all&sort=updated&direction=desc&per_page=${request.pageSize}&page=${request.page}`,
    {
      method: 'GET'
    },
    currentConfig
  );

  const items = Array.isArray(response) ? response : [];
  const issues = [];
  for (const item of items) {
    if (!item || typeof item !== 'object' || item.pull_request) {
      continue;
    }
    issues.push({
      issueNumber: Number(item.number) || 0,
      issueUrl: item.html_url || '',
      title: String(item.title || '').trim(),
      bodyPreview: buildBodyPreview(item.body),
      state: String(item.state || 'open').trim() || 'open',
      commentCount: Number(item.comments) || 0,
      authorLabel: firstNonEmpty(item.user && item.user.login, 'Unknown'),
      updatedAt: firstNonEmpty(item.updated_at, item.created_at),
      updatedAtMs: Date.parse(firstNonEmpty(item.updated_at, item.created_at)) || 0
    });
  }

  return {
    issues,
    page: request.page,
    nextPage: request.page + 1,
    hasMore: items.length >= request.pageSize
  };
}

async function uploadIssueMessageScreenshots(messageRequest, currentConfig) {
  if (!messageRequest.screenshots.length) {
    return [];
  }

  const releaseDescriptor = buildDiagnosticsReleaseDescriptor(new Date(), currentConfig);
  const releaseRecord = await ensureDiagnosticsRelease(releaseDescriptor, currentConfig);
  const uploads = [];

  for (const file of messageRequest.screenshots) {
    const extension = resolveFileExtension(file.originalname, file.mimetype);
    const baseName = buildBundleBaseName(messageRequest.requestId, file.originalname || 'screenshot');
    const assetName = `${baseName}${extension}`;
    const upload = await uploadReleaseAsset({
      owner: currentConfig.diagnosticsOwner,
      repo: currentConfig.diagnosticsRepo,
      uploadUrl: releaseRecord.upload_url,
      assetName,
      contentType: file.mimetype || 'application/octet-stream',
      content: file.buffer
    }, currentConfig);
    uploads.push({
      name: file.originalname || assetName,
      url: upload.htmlUrl,
      mimeType: file.mimetype || 'application/octet-stream',
      assetName
    });
  }

  return uploads;
}

function buildIssueMessageCommentBody(messageRequest, uploadedAttachments) {
  const sentAt = new Date().toISOString();
  const sections = [];

  if (messageRequest.messageText) {
    sections.push(messageRequest.messageText);
  }

  if (uploadedAttachments.length > 0) {
    sections.push([
      '### 截图',
      ...uploadedAttachments.map((attachment) => `- [${attachment.name}](${attachment.url})`)
    ].join('\n'));
  }

  sections.push([
    '---',
    '由 SlayTheAmethyst 启动器代发',
    `- 玩家名：${messageRequest.playerName}`,
    `- 启动器版本：${messageRequest.appVersion}`,
    `- 设备：${messageRequest.deviceLabel}`,
    `- 发送时间：${sentAt}`
  ].join('\n'));

  sections.push(
    buildFeedbackProxyMarker({
      origin: 'user',
      issueNumber: messageRequest.issueNumber,
      messageText: messageRequest.messageText,
      playerName: messageRequest.playerName,
      appVersion: messageRequest.appVersion,
      deviceLabel: messageRequest.deviceLabel,
      sentAt,
      attachments: uploadedAttachments
    })
  );

  return {
    body: sections.filter(Boolean).join('\n\n'),
    sentAt
  };
}

function resolveFileExtension(fileName, mimeType) {
  const normalizedName = String(fileName || '').trim();
  const dotIndex = normalizedName.lastIndexOf('.');
  if (dotIndex > 0 && dotIndex < normalizedName.length - 1) {
    return normalizedName.slice(dotIndex);
  }
  const normalizedMime = String(mimeType || '').trim().toLowerCase();
  if (normalizedMime === 'image/png') {
    return '.png';
  }
  if (normalizedMime === 'image/jpeg') {
    return '.jpg';
  }
  if (normalizedMime === 'image/webp') {
    return '.webp';
  }
  return '.bin';
}

function buildBodyPreview(body) {
  const normalized = String(body || '')
    .replace(/\r/g, '\n')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .join(' ')
    .trim();
  if (!normalized) {
    return '';
  }
  return normalized.length <= 140
    ? normalized
    : `${normalized.slice(0, 137).trimEnd()}...`;
}

module.exports = {
  parseIssueMessageRequest,
  parseIssueStateRequest,
  parseIssueBrowseRequest,
  listBrowsableIssues,
  uploadIssueMessageScreenshots,
  buildIssueMessageCommentBody
};
