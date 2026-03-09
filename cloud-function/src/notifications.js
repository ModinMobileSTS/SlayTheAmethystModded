'use strict';

const {
  ensureDiagnosticsRelease,
  findReleaseAssetByName,
  deleteReleaseAsset,
  uploadReleaseAsset,
  downloadReleaseAssetContent,
  findReleaseByTag,
  buildNotificationStateReleaseDescriptor,
  buildNotificationStateAssetName
} = require('./github');
const {
  maybeSendIssueCreatedEmail,
  maybeSendIssueClosedEmail,
  maybeSendIssueCommentEmail
} = require('./mail');
const {
  firstNonEmpty,
  parseBoolean,
  looksLikeEmailAddress,
  httpError,
  summarizeInlineErrorMessage
} = require('./utils');

async function maybeHandleIssueCreatedNotification(submission, issue, preparedIssueBody, currentConfig) {
  const recipient = extractNotificationRecipient(submission.payload);
  if (!recipient) {
    return null;
  }

  const notificationState = buildIssueNotificationStateRecord(
    submission,
    issue,
    preparedIssueBody,
    recipient,
    currentConfig
  );
  const releaseDescriptor = buildNotificationStateReleaseDescriptor(
    issue && issue.created_at ? issue.created_at : new Date(),
    currentConfig
  );

  try {
    await upsertIssueNotificationStateRecord(notificationState, releaseDescriptor, currentConfig);

    const sendResult = await maybeSendIssueCreatedEmail(notificationState, currentConfig);
    notificationState.notifications.created = {
      attemptedAt: new Date().toISOString(),
      sentAt: sendResult.sentAt,
      messageId: sendResult.messageId,
      skippedReason: sendResult.skippedReason,
      error: sendResult.error
    };
    notificationState.updatedAt = new Date().toISOString();

    await upsertIssueNotificationStateRecord(notificationState, releaseDescriptor, currentConfig);

    return {
      enabled: true,
      emailStored: true,
      issueNumber: notificationState.issue.number,
      createdEmailSent: Boolean(sendResult.sentAt),
      createdEmailSkippedReason: sendResult.skippedReason || null,
      createdEmailError: sendResult.error || null
    };
  } catch (error) {
    return {
      enabled: true,
      emailStored: false,
      issueNumber: notificationState.issue.number,
      createdEmailSent: false,
      createdEmailError: summarizeInlineErrorMessage(error)
    };
  }
}

async function maybeHandleIssueClosedNotification(payload, currentConfig) {
  const issue = payload && payload.issue ? payload.issue : {};
  const repository = payload && payload.repository ? payload.repository : {};
  if (!Number.isInteger(issue.number) && !Number.isFinite(Number(issue.number))) {
    return {
      enabled: false,
      reason: 'missing_issue_number'
    };
  }

  try {
    const lookup = await findIssueNotificationStateRecord(repository, issue, currentConfig);
    if (!lookup) {
      return {
        enabled: false,
        issueNumber: Number(issue.number),
        reason: 'notification_state_not_found'
      };
    }

    const notificationState = normalizeNotificationStateRecord(lookup.record);
    if (notificationState.notifications.closed && notificationState.notifications.closed.sentAt) {
      return {
        enabled: true,
        issueNumber: notificationState.issue.number,
        alreadySent: true,
        closedEmailSent: true
      };
    }

    notificationState.issue.title = firstNonEmpty(issue.title, notificationState.issue.title);
    notificationState.issue.htmlUrl = firstNonEmpty(issue.html_url, notificationState.issue.htmlUrl);
    notificationState.issue.state = firstNonEmpty(issue.state, 'closed');
    notificationState.issue.closedAt = firstNonEmpty(issue.closed_at, new Date().toISOString());

    const sendResult = await maybeSendIssueClosedEmail(notificationState, currentConfig);
    notificationState.notifications.closed = {
      attemptedAt: new Date().toISOString(),
      sentAt: sendResult.sentAt,
      messageId: sendResult.messageId,
      skippedReason: sendResult.skippedReason,
      error: sendResult.error
    };
    notificationState.updatedAt = new Date().toISOString();

    await upsertIssueNotificationStateRecord(notificationState, lookup.releaseDescriptor, currentConfig);

    return {
      enabled: true,
      issueNumber: notificationState.issue.number,
      closedEmailSent: Boolean(sendResult.sentAt),
      closedEmailSkippedReason: sendResult.skippedReason || null,
      closedEmailError: sendResult.error || null
    };
  } catch (error) {
    return {
      enabled: true,
      issueNumber: Number(issue.number),
      closedEmailSent: false,
      closedEmailError: summarizeInlineErrorMessage(error)
    };
  }
}

async function maybeHandleIssueCommentNotification(payload, currentConfig) {
  const issue = payload && payload.issue ? payload.issue : {};
  const repository = payload && payload.repository ? payload.repository : {};
  const comment = payload && payload.comment ? payload.comment : {};
  const commentId = Number(comment.id);
  if (!Number.isFinite(commentId) || commentId <= 0) {
    return {
      enabled: false,
      reason: 'missing_comment_id'
    };
  }

  try {
    const lookup = await findIssueNotificationStateRecord(repository, issue, currentConfig);
    if (!lookup) {
      return {
        enabled: false,
        issueNumber: Number(issue.number),
        reason: 'notification_state_not_found'
      };
    }

    const notificationState = normalizeNotificationStateRecord(lookup.record);
    const deliveredCommentIds = notificationState.notifications.comments.deliveredCommentIds;
    if (deliveredCommentIds.includes(commentId)) {
      return {
        enabled: true,
        issueNumber: notificationState.issue.number,
        alreadySent: true,
        commentEmailSent: true
      };
    }

    notificationState.issue.title = firstNonEmpty(issue.title, notificationState.issue.title);
    notificationState.issue.htmlUrl = firstNonEmpty(issue.html_url, notificationState.issue.htmlUrl);
    notificationState.issue.state = firstNonEmpty(issue.state, notificationState.issue.state);

    const sendResult = await maybeSendIssueCommentEmail(notificationState, comment, currentConfig);
    notificationState.notifications.comments.lastAttemptedAt = new Date().toISOString();
    notificationState.notifications.comments.lastMessageId = sendResult.messageId;
    notificationState.notifications.comments.lastSkippedReason = sendResult.skippedReason;
    notificationState.notifications.comments.lastError = sendResult.error;
    if (sendResult.sentAt) {
      notificationState.notifications.comments.lastSentAt = sendResult.sentAt;
      notificationState.notifications.comments.deliveredCommentIds = appendRecentNumericId(
        deliveredCommentIds,
        commentId
      );
    }
    notificationState.updatedAt = new Date().toISOString();

    await upsertIssueNotificationStateRecord(notificationState, lookup.releaseDescriptor, currentConfig);

    return {
      enabled: true,
      issueNumber: notificationState.issue.number,
      commentEmailSent: Boolean(sendResult.sentAt),
      commentEmailSkippedReason: sendResult.skippedReason || null,
      commentEmailError: sendResult.error || null
    };
  } catch (error) {
    return {
      enabled: true,
      issueNumber: Number(issue.number),
      commentEmailSent: false,
      commentEmailError: summarizeInlineErrorMessage(error)
    };
  }
}

function extractNotificationRecipient(payload) {
  const feedback = payload && payload.feedback ? payload.feedback : {};
  const email = String(feedback.email || '').trim();
  const notifyByEmail = parseBoolean(feedback.notifyByEmail, false);
  if (!notifyByEmail || !looksLikeEmailAddress(email)) {
    return null;
  }
  return {
    email
  };
}

function buildIssueNotificationStateRecord(submission, issue, preparedIssueBody, recipient, currentConfig) {
  const createdAt = firstNonEmpty(issue && issue.created_at, new Date().toISOString());
  const feedback = submission && submission.payload && submission.payload.feedback
    ? submission.payload.feedback
    : {};
  return {
    version: 1,
    requestId: submission.requestId,
    source: firstNonEmpty(submission.payload && submission.payload.source, 'slay-the-amethyst-android'),
    repository: {
      owner: currentConfig.githubOwner,
      repo: currentConfig.githubRepo
    },
    issue: {
      number: Number(issue.number),
      title: firstNonEmpty(issue.title, submission.issueTitle),
      htmlUrl: firstNonEmpty(issue.html_url),
      state: firstNonEmpty(issue.state, 'open'),
      createdAt,
      closedAt: null
    },
    recipient: {
      email: recipient.email
    },
    feedback: {
      summary: firstNonEmpty(feedback.summary, submission.issueTitle),
      detail: firstNonEmpty(feedback.detail),
      reproductionSteps: firstNonEmpty(feedback.reproductionSteps),
      formattedIssueBody: firstNonEmpty(preparedIssueBody)
    },
    notifications: {
      created: {
        attemptedAt: null,
        sentAt: null,
        messageId: null,
        skippedReason: null,
        error: null
      },
      closed: {
        attemptedAt: null,
        sentAt: null,
        messageId: null,
        skippedReason: null,
        error: null
      },
      comments: {
        lastAttemptedAt: null,
        lastSentAt: null,
        lastMessageId: null,
        lastSkippedReason: null,
        lastError: null,
        deliveredCommentIds: []
      }
    },
    createdAt,
    updatedAt: createdAt
  };
}

async function upsertIssueNotificationStateRecord(record, releaseDescriptor, currentConfig) {
  const releaseRecord = await ensureDiagnosticsRelease(releaseDescriptor, currentConfig);
  const assetName = buildNotificationStateAssetName(
    record.repository.owner,
    record.repository.repo,
    record.issue.number
  );
  const existingAsset = await findReleaseAssetByName(releaseRecord, assetName, {
    owner: releaseDescriptor.owner,
    repo: releaseDescriptor.repo
  }, currentConfig);
  if (existingAsset && existingAsset.id) {
    await deleteReleaseAsset({
      owner: releaseDescriptor.owner,
      repo: releaseDescriptor.repo,
      assetId: existingAsset.id
    }, currentConfig);
  }

  return uploadReleaseAsset({
    owner: releaseDescriptor.owner,
    repo: releaseDescriptor.repo,
    uploadUrl: releaseRecord.upload_url,
    assetName,
    contentType: 'application/json; charset=utf-8',
    content: Buffer.from(JSON.stringify(record, null, 2), 'utf8')
  }, currentConfig);
}

async function findIssueNotificationStateRecord(repository, issue, currentConfig) {
  const createdAt = firstNonEmpty(issue && issue.created_at);
  if (!createdAt) {
    return null;
  }

  const owner = firstNonEmpty(
    repository && repository.owner && repository.owner.login,
    repository && repository.owner && repository.owner.name,
    currentConfig.githubOwner
  );
  const repo = firstNonEmpty(repository && repository.name, currentConfig.githubRepo);
  const releaseDescriptor = buildNotificationStateReleaseDescriptor(createdAt, currentConfig);
  const releaseRecord = await findReleaseByTag(releaseDescriptor, currentConfig);
  if (!releaseRecord) {
    return null;
  }

  const assetName = buildNotificationStateAssetName(owner, repo, issue.number);
  const asset = await findReleaseAssetByName(releaseRecord, assetName, {
    owner: releaseDescriptor.owner,
    repo: releaseDescriptor.repo
  }, currentConfig);
  if (!asset || !asset.id) {
    return null;
  }

  const content = await downloadReleaseAssetContent(asset, currentConfig);
  let parsed;
  try {
    parsed = JSON.parse(content.toString('utf8'));
  } catch (_error) {
    throw httpError(502, `Notification state asset ${assetName} is not valid JSON`);
  }
  if (!parsed || typeof parsed !== 'object') {
    throw httpError(502, `Notification state asset ${assetName} is not valid JSON`);
  }

  return {
    releaseDescriptor,
    record: parsed
  };
}

function normalizeNotificationStateRecord(record) {
  const normalized = record && typeof record === 'object' ? record : {};
  const notifications = normalized.notifications && typeof normalized.notifications === 'object'
    ? normalized.notifications
    : {};
  return {
    ...normalized,
    feedback: {
      summary: firstNonEmpty(normalized.feedback && normalized.feedback.summary),
      detail: firstNonEmpty(normalized.feedback && normalized.feedback.detail),
      reproductionSteps: firstNonEmpty(normalized.feedback && normalized.feedback.reproductionSteps),
      formattedIssueBody: firstNonEmpty(normalized.feedback && normalized.feedback.formattedIssueBody)
    },
    notifications: {
      created: {
        attemptedAt: notifications.created && notifications.created.attemptedAt || null,
        sentAt: notifications.created && notifications.created.sentAt || null,
        messageId: notifications.created && notifications.created.messageId || null,
        skippedReason: notifications.created && notifications.created.skippedReason || null,
        error: notifications.created && notifications.created.error || null
      },
      closed: {
        attemptedAt: notifications.closed && notifications.closed.attemptedAt || null,
        sentAt: notifications.closed && notifications.closed.sentAt || null,
        messageId: notifications.closed && notifications.closed.messageId || null,
        skippedReason: notifications.closed && notifications.closed.skippedReason || null,
        error: notifications.closed && notifications.closed.error || null
      },
      comments: {
        lastAttemptedAt: notifications.comments && notifications.comments.lastAttemptedAt || null,
        lastSentAt: notifications.comments && notifications.comments.lastSentAt || null,
        lastMessageId: notifications.comments && notifications.comments.lastMessageId || null,
        lastSkippedReason: notifications.comments && notifications.comments.lastSkippedReason || null,
        lastError: notifications.comments && notifications.comments.lastError || null,
        deliveredCommentIds: Array.isArray(notifications.comments && notifications.comments.deliveredCommentIds)
          ? notifications.comments.deliveredCommentIds.map((value) => Number(value)).filter((value) => Number.isFinite(value) && value > 0)
          : []
      }
    }
  };
}

function appendRecentNumericId(values, nextValue, maxItems = 50) {
  const normalized = Array.isArray(values)
    ? values.map((value) => Number(value)).filter((value) => Number.isFinite(value) && value > 0)
    : [];
  normalized.push(Number(nextValue));
  const deduped = Array.from(new Set(normalized));
  return deduped.slice(-maxItems);
}

module.exports = {
  maybeHandleIssueCreatedNotification,
  maybeHandleIssueClosedNotification,
  maybeHandleIssueCommentNotification,
  extractNotificationRecipient,
  buildIssueNotificationStateRecord,
  upsertIssueNotificationStateRecord,
  findIssueNotificationStateRecord,
  normalizeNotificationStateRecord,
  appendRecentNumericId
};
