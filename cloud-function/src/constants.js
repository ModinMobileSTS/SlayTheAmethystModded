'use strict';

const path = require('node:path');

const APP_NAME = 'sts-feedback-relay';
const DEFAULT_PORT = 9000;
const DEFAULT_BUNDLE_MAX_BYTES = 25 * 1024 * 1024;
const MAIL_LOGO_CID = 'slay-the-amethyst-logo@inline';
const MAIL_LOGO_PATH = path.join(__dirname, '..', 'assets', 'logo.png');

module.exports = {
  APP_NAME,
  DEFAULT_PORT,
  DEFAULT_BUNDLE_MAX_BYTES,
  MAIL_LOGO_CID,
  MAIL_LOGO_PATH
};
