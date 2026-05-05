'use strict';

const express = require('express');
const multer = require('multer');

const { APP_NAME } = require('./constants');
const {
  captureRawRequestBody,
  enforceSharedSecret,
  enforceGithubWebhookSecret,
  matchesTargetRepository
} = require('./security');
const {
  parseSubmissionRequest,
  maybeUploadBundle,
  uploadSubmissionScreenshots,
  buildIssueLabels,
  prepareIssueBody,
  appendRelaySection
} = require('./submission');
const {
  parseIssueMessageRequest,
  parseIssueStateRequest,
  parseIssueBrowseRequest,
  listBrowsableIssues,
  uploadIssueMessageScreenshots,
  uploadIssueMessageLogBundle,
  buildIssueMessageCommentBody
} = require('./feedbackIssues');
const {
  createGithubIssue,
  createGithubIssueComment,
  updateGithubIssueState
} = require('./github');
const {
  maybeHandleIssueCreatedNotification,
  maybeHandleIssueClosedNotification,
  maybeHandleIssueCommentNotification
} = require('./notifications');
const {
  normalizeStatusCode,
  normalizeErrorCode,
  normalizeErrorMessage
} = require('./utils');

function createApp(config) {
  const upload = multer({
    storage: multer.memoryStorage(),
    limits: {
      fileSize: config.bundleMaxBytes
    }
  });

  const app = express();

  app.disable('x-powered-by');
  app.use(express.json({
    limit: '1mb',
    verify: captureRawRequestBody
  }));

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

      const files = req.files && typeof req.files === 'object' ? req.files : {};
      const bundleFile = Array.isArray(files.bundle) && files.bundle.length > 0
        ? files.bundle[0]
        : null;
      const screenshotFiles = Array.isArray(files.screenshots) ? files.screenshots : [];

      const submission = parseSubmissionRequest(req);
      const bundleRecord = await maybeUploadBundle(submission, bundleFile, config);
      const screenshotAttachments = await uploadSubmissionScreenshots(submission, screenshotFiles, config);
      const labels = buildIssueLabels(submission.payload, config.staticLabels);
      const preparedIssueBody = prepareIssueBody(submission.issueBody, submission.payload);
      const issueBody = appendRelaySection(
        preparedIssueBody,
        submission.requestId,
        bundleRecord,
        screenshotAttachments
      );
      const issue = await createGithubIssue({
        title: submission.issueTitle,
        body: issueBody,
        labels
      }, config);
      const mailNotification = await maybeHandleIssueCreatedNotification(
        submission,
        issue,
        preparedIssueBody,
        config
      );

      res.status(201).json({
        ok: true,
        requestId: submission.requestId,
        issueNumber: issue.number,
        issueUrl: issue.html_url,
        issue: {
          number: issue.number,
          html_url: issue.html_url
        },
        diagnosticBundle: bundleRecord,
        screenshots: screenshotAttachments,
        mailNotification
      });
    } catch (error) {
      next(error);
    }
  };

  const handleGithubWebhook = async (req, res, next) => {
    try {
      enforceGithubWebhookSecret(req, config);

      const eventName = String(req.get('x-github-event') || '').trim();
      if (eventName === 'ping') {
        res.json({
          ok: true,
          event: eventName
        });
        return;
      }

      if (eventName === 'issue_comment') {
        const payload = req.body && typeof req.body === 'object' ? req.body : {};
        if (!matchesTargetRepository(payload.repository, config)) {
          res.status(202).json({
            ok: true,
            ignored: true,
            reason: 'repository_mismatch'
          });
          return;
        }

        if (String(payload.action || '') !== 'created') {
          res.status(202).json({
            ok: true,
            ignored: true,
            reason: 'unsupported_action',
            action: payload.action || 'unknown'
          });
          return;
        }

        const mailNotification = await maybeHandleIssueCommentNotification(payload, config);
        res.json({
          ok: true,
          event: eventName,
          action: payload.action,
          mailNotification
        });
        return;
      }

      if (eventName !== 'issues') {
        res.status(202).json({
          ok: true,
          ignored: true,
          reason: 'unsupported_event',
          event: eventName || 'unknown'
        });
        return;
      }

      const payload = req.body && typeof req.body === 'object' ? req.body : {};
      if (!matchesTargetRepository(payload.repository, config)) {
        res.status(202).json({
          ok: true,
          ignored: true,
          reason: 'repository_mismatch'
        });
        return;
      }

      if (String(payload.action || '') !== 'closed') {
        res.status(202).json({
          ok: true,
          ignored: true,
          reason: 'unsupported_action',
          action: payload.action || 'unknown'
        });
        return;
      }

      const mailNotification = await maybeHandleIssueClosedNotification(payload, config);
      res.json({
        ok: true,
        event: eventName,
        action: payload.action,
        mailNotification
      });
    } catch (error) {
      next(error);
    }
  };

  const handleIssueMessage = async (req, res, next) => {
    try {
      enforceSharedSecret(req, config);

      const messageRequest = parseIssueMessageRequest(req);
      const attachments = await uploadIssueMessageScreenshots(messageRequest, config);
      const logBundleAttachment = await uploadIssueMessageLogBundle(messageRequest, config);
      const commentRequest = buildIssueMessageCommentBody(messageRequest, attachments, logBundleAttachment);
      const comment = await createGithubIssueComment(
        messageRequest.issueNumber,
        commentRequest.body,
        config
      );

      const allAttachments = logBundleAttachment
        ? [...attachments, logBundleAttachment]
        : attachments;

      res.status(201).json({
        ok: true,
        issueNumber: messageRequest.issueNumber,
        commentId: Number(comment.id),
        commentUrl: comment.html_url || null,
        createdAt: comment.created_at || commentRequest.sentAt,
        attachments: allAttachments
      });
    } catch (error) {
      next(error);
    }
  };

  const handleIssueState = async (req, res, next) => {
    try {
      enforceSharedSecret(req, config);

      const stateRequest = parseIssueStateRequest(req);
      const issue = await updateGithubIssueState(
        stateRequest.issueNumber,
        stateRequest.targetState,
        config
      );

      res.json({
        ok: true,
        issueNumber: Number(issue.number),
        issueUrl: issue.html_url || null,
        state: issue.state || stateRequest.targetState,
        updatedAt: issue.updated_at || new Date().toISOString()
      });
    } catch (error) {
      next(error);
    }
  };

  const handleIssueBrowse = async (req, res, next) => {
    try {
      enforceSharedSecret(req, config);

      const browseRequest = parseIssueBrowseRequest(req);
      const result = await listBrowsableIssues(browseRequest, config);
      res.json({
        ok: true,
        ...result
      });
    } catch (error) {
      next(error);
    }
  };

  app.post('/', upload.fields([
    { name: 'bundle', maxCount: 1 },
    { name: 'screenshots', maxCount: 4 }
  ]), handleFeedbackSubmission);
  app.post('/api/sts-feedback', upload.fields([
    { name: 'bundle', maxCount: 1 },
    { name: 'screenshots', maxCount: 4 }
  ]), handleFeedbackSubmission);
  app.get('/api/feedback-issues/browse', handleIssueBrowse);
  app.post('/api/feedback-issues/message', upload.fields([
    { name: 'screenshots', maxCount: 4 },
    { name: 'log_bundle', maxCount: 1 }
  ]), handleIssueMessage);
  app.post('/api/feedback-issues/state', handleIssueState);
  app.post('/github/webhook', handleGithubWebhook);

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

  return app;
}

module.exports = {
  createApp
};
