'use strict';

const { randomUUID } = require('node:crypto');

const {
  ensureDiagnosticsRelease,
  uploadReleaseAsset,
  buildBundleBaseName,
  buildDiagnosticsReleaseDescriptor
} = require('./github');
const {
  firstNonEmpty,
  httpError,
  normalizeSlug,
  parseBoolean,
  looksLikeEmailAddress,
  removeMarkdownSections,
  safeJsonParse,
  isFeatureRequest,
  summarizeInlineErrorMessage
} = require('./utils');

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
    payload: sanitizePayloadForArtifactStorage(submission.payload)
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
  let prepared = removeMarkdownSections(issueBody, [
    '联系方式',
    '邮箱通知'
  ]);

  if (isFeatureRequest(feedback)) {
    prepared = removeMarkdownSections(prepared, [
      '环境信息',
      '启用模组快照',
      'latest.log 关键行'
    ]);
  }

  const notificationSection = buildNotificationSection(feedback);
  return [String(prepared || '').trim(), notificationSection]
    .filter(Boolean)
    .join('\n\n')
    .trim();
}

function buildNotificationSection(feedback) {
  if (parseBoolean(feedback && feedback.notifyByEmail, false) && looksLikeEmailAddress(feedback && feedback.email)) {
    return [
      '## 邮件通知',
      '- 状态：已开启'
    ].join('\n');
  }
  return [
    '## 邮件通知',
    '- 状态：未开启'
  ].join('\n');
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

function sanitizePayloadForArtifactStorage(payload) {
  const clone = safeJsonParse(JSON.stringify(payload || {}));
  if (
    clone &&
    typeof clone === 'object' &&
    clone.feedback &&
    typeof clone.feedback === 'object'
  ) {
    clone.feedback.email = '';
  }
  return clone;
}

module.exports = {
  parseSubmissionRequest,
  parsePayloadJson,
  maybeUploadBundle,
  buildIssueLabels,
  prepareIssueBody,
  buildNotificationSection,
  appendRelaySection,
  sanitizePayloadForArtifactStorage
};
