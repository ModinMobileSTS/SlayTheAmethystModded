'use strict';

function escapeHtml(value) {
  return String(value || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function escapeHtmlAttribute(value) {
  return escapeHtml(value).replace(/`/g, '&#96;');
}

function safeJsonParse(rawText) {
  try {
    return JSON.parse(rawText);
  } catch (_error) {
    return { raw: rawText };
  }
}

function parsePositiveInteger(rawValue, fallbackValue) {
  const parsed = Number.parseInt(String(rawValue || '').trim(), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallbackValue;
}

function parseBoolean(rawValue, fallbackValue) {
  if (typeof rawValue === 'boolean') {
    return rawValue;
  }
  const normalized = String(rawValue || '').trim().toLowerCase();
  if (!normalized) {
    return fallbackValue;
  }
  if (normalized === 'true' || normalized === '1' || normalized === 'yes') {
    return true;
  }
  if (normalized === 'false' || normalized === '0' || normalized === 'no') {
    return false;
  }
  return fallbackValue;
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

function looksLikeEmailAddress(value) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(String(value || '').trim());
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

function summarizeErrorWithCause(error) {
  const details = [];
  const message = normalizeErrorMessage(error)
    .replace(/\s+/g, ' ')
    .trim();

  if (message) {
    details.push(message);
  }

  let cause = error && error.cause;
  let depth = 0;
  while (cause && depth < 3) {
    const parts = [];
    if (cause.code) {
      parts.push(`code=${String(cause.code).trim()}`);
    }
    if (cause.errno && String(cause.errno).trim() !== String(cause.code || '').trim()) {
      parts.push(`errno=${String(cause.errno).trim()}`);
    }
    if (cause.hostname) {
      parts.push(`host=${String(cause.hostname).trim()}`);
    }
    if (cause.address) {
      parts.push(`address=${String(cause.address).trim()}`);
    }
    if (cause.port) {
      parts.push(`port=${String(cause.port).trim()}`);
    }
    if (cause.message) {
      const causeMessage = String(cause.message).replace(/\s+/g, ' ').trim();
      if (causeMessage && !parts.includes(causeMessage)) {
        parts.push(causeMessage);
      }
    }
    if (parts.length) {
      details.push(parts.join(', '));
    }
    cause = cause.cause;
    depth += 1;
  }

  return details.join(' | ').trim();
}

function buildFeedbackProxyMarker(payload) {
  return `<!-- sts-feedback-proxy:${JSON.stringify(payload)} -->`;
}

function parseFeedbackProxyMarker(text) {
  const match = /<!--\s*sts-feedback-proxy:(\{.*?\})\s*-->/s.exec(String(text || ''));
  if (!match) {
    return null;
  }
  try {
    return JSON.parse(match[1]);
  } catch (_error) {
    return null;
  }
}

function stripFeedbackProxyMarker(text) {
  return String(text || '')
    .replace(/<!--\s*sts-feedback-proxy:(\{.*?\})\s*-->/gs, '')
    .trim();
}

module.exports = {
  escapeHtml,
  escapeHtmlAttribute,
  safeJsonParse,
  parsePositiveInteger,
  parseBoolean,
  parseCsv,
  normalizeReleasePrefix,
  normalizePrivateKey,
  base64UrlEncodeJson,
  base64UrlEncodeBuffer,
  isFeatureRequest,
  removeMarkdownSections,
  parseLevelTwoHeading,
  normalizeHeadingTitle,
  firstNonEmpty,
  normalizeSlug,
  looksLikeEmailAddress,
  encodePath,
  encodePathSegment,
  readOptionalEnv,
  requireEnv,
  httpError,
  normalizeStatusCode,
  normalizeErrorCode,
  normalizeErrorMessage,
  summarizeInlineErrorMessage,
  summarizeErrorWithCause,
  buildFeedbackProxyMarker,
  parseFeedbackProxyMarker,
  stripFeedbackProxyMarker
};
