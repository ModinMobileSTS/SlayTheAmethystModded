'use strict';

const {
  DEFAULT_PORT,
  DEFAULT_BUNDLE_MAX_BYTES
} = require('./constants');
const {
  parsePositiveInteger,
  parseBoolean,
  parseCsv,
  normalizeReleasePrefix,
  normalizePrivateKey,
  readOptionalEnv,
  requireEnv
} = require('./utils');

function loadConfig() {
  const githubOwner = requireEnv('GITHUB_OWNER');
  const githubRepo = requireEnv('GITHUB_REPO');
  const diagnosticsOwner = readOptionalEnv('GITHUB_DIAGNOSTICS_OWNER') || githubOwner;
  const diagnosticsRepo = readOptionalEnv('GITHUB_DIAGNOSTICS_REPO') || 'SlayTheDiagnostics';
  const diagnosticsBranch = readOptionalEnv('GITHUB_DIAGNOSTICS_BRANCH') || 'main';

  return {
    port: parsePositiveInteger(process.env.PORT, DEFAULT_PORT),
    bundleMaxBytes: parsePositiveInteger(process.env.BUNDLE_MAX_BYTES, DEFAULT_BUNDLE_MAX_BYTES),
    sharedSecret: readOptionalEnv('FEEDBACK_SHARED_SECRET'),
    githubWebhookSecret: readOptionalEnv('GITHUB_WEBHOOK_SECRET'),
    githubAuth: loadGithubAuthConfig(),
    mail: loadMailConfig(),
    githubOwner,
    githubRepo,
    staticLabels: parseCsv(process.env.GITHUB_ISSUE_LABELS, ['feedback', 'client:android']),
    diagnosticsOwner,
    diagnosticsRepo,
    diagnosticsBranch,
    diagnosticsReleasePrefix: normalizeReleasePrefix(
      readOptionalEnv('GITHUB_DIAGNOSTICS_RELEASE_PREFIX') || 'feedback'
    ),
    notificationStateOwner: readOptionalEnv('GITHUB_NOTIFICATION_STATE_OWNER') || diagnosticsOwner,
    notificationStateRepo: readOptionalEnv('GITHUB_NOTIFICATION_STATE_REPO') || diagnosticsRepo,
    notificationStateBranch: readOptionalEnv('GITHUB_NOTIFICATION_STATE_BRANCH') || diagnosticsBranch,
    notificationStateReleasePrefix: normalizeReleasePrefix(
      readOptionalEnv('GITHUB_NOTIFICATION_STATE_RELEASE_PREFIX') || 'feedback-mail-state'
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

function loadMailConfig() {
  const host = readOptionalEnv('SMTP_HOST');
  const from = readOptionalEnv('SMTP_FROM');
  const username = readOptionalEnv('SMTP_USERNAME');
  const password = readOptionalEnv('SMTP_PASSWORD');
  const replyTo = readOptionalEnv('SMTP_REPLY_TO');
  const hasAnyMailSetting = Boolean(host || from || username || password || replyTo || readOptionalEnv('SMTP_PORT'));

  if (!hasAnyMailSetting) {
    return {
      enabled: false,
      transport: null
    };
  }
  if (!host) {
    throw new Error('Missing required environment variable: SMTP_HOST');
  }
  if (!from) {
    throw new Error('Missing required environment variable: SMTP_FROM');
  }
  if ((username && !password) || (!username && password)) {
    throw new Error('SMTP_USERNAME and SMTP_PASSWORD must be configured together');
  }

  return {
    enabled: true,
    host,
    port: parsePositiveInteger(process.env.SMTP_PORT, 465),
    secure: parseBoolean(process.env.SMTP_SECURE, true),
    username,
    password,
    from,
    replyTo,
    transport: null
  };
}

module.exports = {
  loadConfig,
  loadGithubAuthConfig,
  loadMailConfig
};
