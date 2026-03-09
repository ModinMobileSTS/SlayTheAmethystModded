'use strict';

const { createHmac, timingSafeEqual } = require('node:crypto');

const {
  firstNonEmpty,
  httpError
} = require('./utils');

function captureRawRequestBody(req, _res, buffer) {
  if (buffer && buffer.length > 0) {
    req.rawBody = Buffer.from(buffer);
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

function enforceGithubWebhookSecret(req, currentConfig) {
  if (!currentConfig.githubWebhookSecret) {
    throw httpError(503, 'GitHub webhook secret not configured');
  }

  const providedSignature = String(req.get('x-hub-signature-256') || '').trim();
  if (!providedSignature.startsWith('sha256=')) {
    throw httpError(401, 'Invalid X-Hub-Signature-256');
  }

  const rawBody = Buffer.isBuffer(req.rawBody) ? req.rawBody : Buffer.from('', 'utf8');
  const expectedSignature = `sha256=${createHmac('sha256', currentConfig.githubWebhookSecret).update(rawBody).digest('hex')}`;
  const providedBuffer = Buffer.from(providedSignature, 'utf8');
  const expectedBuffer = Buffer.from(expectedSignature, 'utf8');
  if (
    providedBuffer.length !== expectedBuffer.length ||
    !timingSafeEqual(providedBuffer, expectedBuffer)
  ) {
    throw httpError(401, 'GitHub webhook signature mismatch');
  }
}

function matchesTargetRepository(repository, currentConfig) {
  const owner = firstNonEmpty(
    repository && repository.owner && repository.owner.login,
    repository && repository.owner && repository.owner.name
  );
  const repo = firstNonEmpty(repository && repository.name);
  return owner.toLowerCase() === currentConfig.githubOwner.toLowerCase() &&
    repo.toLowerCase() === currentConfig.githubRepo.toLowerCase();
}

module.exports = {
  captureRawRequestBody,
  enforceSharedSecret,
  enforceGithubWebhookSecret,
  matchesTargetRepository
};
