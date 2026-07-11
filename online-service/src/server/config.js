'use strict';

const path = require('path');

const DEFAULT_PORT = 8787;
const DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30;
const DEFAULT_OFFLINE_TIMEOUT_SECONDS = 90;
const DEFAULT_QQ_GROUP_NUMBER = '1029305387';
const DEFAULT_EASYTIER_CONFIG_SERVER_SCHEME = 'udp';
const DEFAULT_EASYTIER_CONFIG_SERVER_PORT = 22020;
const DEFAULT_EASYTIER_ENTRY_NODE_SCHEME = 'tcp';
const DEFAULT_EASYTIER_ENTRY_NODE_PORT = 11010;
const DEFAULT_EASYTIER_CONNECT_TIMEOUT_SECONDS = 12;
const DEFAULT_EASYTIER_STATUS_POLL_INTERVAL_SECONDS = 5;
const DEFAULT_EASYTIER_DEFAULT_MODE = 'room';
const DEFAULT_EASYTIER_SESSION_TTL_SECONDS = 30 * 60;
const DEFAULT_EASYTIER_WEB_EMBED_API_SERVER_PORT = 11211;
const DEFAULT_EASYTIER_WEB_EMBED_API_SERVER_ADDR = '127.0.0.1';
const DEFAULT_EASYTIER_RUNTIME_DATA_DIR = './data/easytier-runtime';
const DEFAULT_EASYTIER_SHARED_NODE_INSTANCE_NAME = 'sts-online-shared-node';
const DEFAULT_EASYTIER_SHARED_NODE_HOSTNAME = 'sts-online-shared-node';
const DEFAULT_EASYTIER_SHARED_NODE_NETWORK_NAME = 'sts-online-shared-node';
const DEFAULT_EASYTIER_SHARED_NODE_RPC_PORTAL = '127.0.0.1:15888';

function loadConfig(env = process.env) {
  const heartbeatIntervalSeconds = parsePositiveInteger(
    env.PRESENCE_HEARTBEAT_INTERVAL_SECONDS,
    DEFAULT_HEARTBEAT_INTERVAL_SECONDS
  );

  return {
    host: firstNonEmpty(env.HOST, '0.0.0.0'),
    port: parsePositiveInteger(env.PORT, DEFAULT_PORT),
    publicBaseUrl: normalizeOptionalBaseUrl(env.PUBLIC_BASE_URL),
    dbPath: path.resolve(firstNonEmpty(env.PRESENCE_DB_PATH, './data/presence.sqlite')),
    presenceHeartbeatIntervalSeconds: heartbeatIntervalSeconds,
    presenceOfflineTimeoutSeconds: parsePositiveInteger(
      env.PRESENCE_OFFLINE_TIMEOUT_SECONDS,
      Math.max(DEFAULT_OFFLINE_TIMEOUT_SECONDS, heartbeatIntervalSeconds * 3)
    ),
    qqGroupNumber: normalizeQqGroupNumber(env.QQ_GROUP_NUMBER, DEFAULT_QQ_GROUP_NUMBER),
    presencePanelToken: firstNonEmpty(env.PRESENCE_PANEL_TOKEN, env.FEEDBACK_SHARED_SECRET),
    logLevel: firstNonEmpty(env.LOG_LEVEL, 'info'),
    maxSessionsReturned: parsePositiveInteger(env.PRESENCE_MAX_SESSIONS_RETURNED, 1000),
    panelSnapshotPushIntervalSeconds: parsePositiveInteger(
      env.PRESENCE_PANEL_SNAPSHOT_PUSH_INTERVAL_SECONDS,
      2
    ),
    panelStatsPushIntervalSeconds: parsePositiveInteger(
      env.PRESENCE_PANEL_STATS_PUSH_INTERVAL_SECONDS,
      300
    ),
    easyTierEnabled: parseBoolean(env.EASYTIER_ENABLED, false),
    easyTierRoomApiBaseUrl: normalizeOptionalBaseUrl(env.EASYTIER_ROOM_API_BASE_URL),
    easyTierWebConsoleApiBaseUrl: normalizeOptionalBaseUrl(env.EASYTIER_WEB_CONSOLE_API_BASE_URL),
    easyTierConfigServerUrl: normalizeOptionalNetworkUrl(env.EASYTIER_CONFIG_SERVER_URL),
    easyTierConfigServerScheme: normalizeOptionalScheme(
      env.EASYTIER_CONFIG_SERVER_SCHEME,
      DEFAULT_EASYTIER_CONFIG_SERVER_SCHEME
    ),
    easyTierConfigServerPort: parsePositiveInteger(
      env.EASYTIER_CONFIG_SERVER_PORT,
      DEFAULT_EASYTIER_CONFIG_SERVER_PORT
    ),
    easyTierEntryNodeUrl: normalizeOptionalNetworkUrl(env.EASYTIER_ENTRY_NODE_URL),
    easyTierEntryNodeScheme: normalizeOptionalScheme(
      env.EASYTIER_ENTRY_NODE_SCHEME,
      DEFAULT_EASYTIER_ENTRY_NODE_SCHEME
    ),
    easyTierEntryNodePort: parsePositiveInteger(
      env.EASYTIER_ENTRY_NODE_PORT,
      DEFAULT_EASYTIER_ENTRY_NODE_PORT
    ),
    easyTierConnectTimeoutSeconds: parsePositiveInteger(
      env.EASYTIER_CONNECT_TIMEOUT_SECONDS,
      DEFAULT_EASYTIER_CONNECT_TIMEOUT_SECONDS
    ),
    easyTierStatusPollIntervalSeconds: parsePositiveInteger(
      env.EASYTIER_STATUS_POLL_INTERVAL_SECONDS,
      DEFAULT_EASYTIER_STATUS_POLL_INTERVAL_SECONDS
    ),
    easyTierSessionTtlSeconds: parsePositiveInteger(
      env.EASYTIER_SESSION_TTL_SECONDS,
      DEFAULT_EASYTIER_SESSION_TTL_SECONDS
    ),
    easyTierAllowSharedCommunityNetwork: parseBoolean(
      env.EASYTIER_ALLOW_SHARED_COMMUNITY_NETWORK,
      false
    ),
    easyTierDefaultMode: normalizeEasyTierDefaultMode(env.EASYTIER_DEFAULT_MODE),
    easyTierManaged: parseBoolean(env.EASYTIER_MANAGED, false),
    easyTierManagedAutoStart: parseBoolean(
      env.EASYTIER_MANAGED_AUTO_START,
      parseBoolean(env.EASYTIER_MANAGED, false)
    ),
    easyTierManagedRestartOnExit: parseBoolean(
      env.EASYTIER_MANAGED_RESTART_ON_EXIT,
      true
    ),
    easyTierManagedStopTimeoutMs: parsePositiveInteger(
      env.EASYTIER_MANAGED_STOP_TIMEOUT_MS,
      5000
    ),
    easyTierManagedRestartDelayMs: parsePositiveInteger(
      env.EASYTIER_MANAGED_RESTART_DELAY_MS,
      2000
    ),
    easyTierRuntimeDataDir: path.resolve(firstNonEmpty(
      env.EASYTIER_RUNTIME_DATA_DIR,
      DEFAULT_EASYTIER_RUNTIME_DATA_DIR
    )),
    easyTierWebEmbedBinaryPath: normalizeOptionalPath(env.EASYTIER_WEB_EMBED_BINARY_PATH),
    easyTierWebEmbedBinaryArgs: parseStringList(env.EASYTIER_WEB_EMBED_BINARY_ARGS),
    easyTierWebEmbedApiServerPort: parsePositiveInteger(
      env.EASYTIER_WEB_EMBED_API_SERVER_PORT,
      DEFAULT_EASYTIER_WEB_EMBED_API_SERVER_PORT
    ),
    easyTierWebEmbedApiServerAddr: firstNonEmpty(
      env.EASYTIER_WEB_EMBED_API_SERVER_ADDR,
      DEFAULT_EASYTIER_WEB_EMBED_API_SERVER_ADDR
    ),
    easyTierWebEmbedDisableWeb: parseBoolean(
      env.EASYTIER_WEB_EMBED_DISABLE_WEB,
      true
    ),
    easyTierWebEmbedInternalAuthToken: firstNonEmpty(
      env.EASYTIER_WEB_EMBED_INTERNAL_AUTH_TOKEN
    ),
    easyTierCoreBinaryPath: normalizeOptionalPath(env.EASYTIER_CORE_BINARY_PATH),
    easyTierCoreBinaryArgs: parseStringList(env.EASYTIER_CORE_BINARY_ARGS),
    easyTierCoreExtraArgs: parseStringList(env.EASYTIER_CORE_EXTRA_ARGS),
    easyTierSharedNodeNetworkName: firstNonEmpty(
      env.EASYTIER_SHARED_NODE_NETWORK_NAME,
      DEFAULT_EASYTIER_SHARED_NODE_NETWORK_NAME
    ),
    easyTierSharedNodeNetworkSecret: firstNonEmpty(env.EASYTIER_SHARED_NODE_NETWORK_SECRET),
    easyTierSharedNodeInstanceName: firstNonEmpty(
      env.EASYTIER_SHARED_NODE_INSTANCE_NAME,
      DEFAULT_EASYTIER_SHARED_NODE_INSTANCE_NAME
    ),
    easyTierSharedNodeHostname: firstNonEmpty(
      env.EASYTIER_SHARED_NODE_HOSTNAME,
      DEFAULT_EASYTIER_SHARED_NODE_HOSTNAME
    ),
    easyTierSharedNodeRpcPortal: firstNonEmpty(
      env.EASYTIER_SHARED_NODE_RPC_PORTAL,
      DEFAULT_EASYTIER_SHARED_NODE_RPC_PORTAL
    ),
    easyTierSharedNodeConfigServer: firstNonEmpty(env.EASYTIER_SHARED_NODE_CONFIG_SERVER)
  };
}

function parsePositiveInteger(rawValue, fallbackValue) {
  const parsed = Number.parseInt(String(rawValue || '').trim(), 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallbackValue;
}

function firstNonEmpty(...values) {
  for (const value of values) {
    const normalized = String(value || '').trim();
    if (normalized) {
      return normalized;
    }
  }
  return '';
}

function normalizeQqGroupNumber(rawValue, fallbackValue) {
  const normalized = firstNonEmpty(rawValue);
  return /^[1-9][0-9]{4,19}$/.test(normalized) ? normalized : fallbackValue;
}

function parseBoolean(rawValue, fallbackValue) {
  const normalized = String(rawValue || '').trim().toLowerCase();
  if (!normalized) {
    return Boolean(fallbackValue);
  }
  if (['1', 'true', 'yes', 'on'].includes(normalized)) {
    return true;
  }
  if (['0', 'false', 'no', 'off'].includes(normalized)) {
    return false;
  }
  return Boolean(fallbackValue);
}

function normalizeOptionalBaseUrl(value) {
  const normalized = String(value || '').trim();
  if (!normalized) {
    return '';
  }
  return normalized.endsWith('/') ? normalized.slice(0, -1) : normalized;
}

function normalizeOptionalNetworkUrl(value) {
  const normalized = String(value || '').trim();
  if (!normalized) {
    return '';
  }
  try {
    const parsed = new URL(normalized);
    return parsed.protocol && parsed.hostname ? normalized : '';
  } catch (_error) {
    return '';
  }
}

function normalizeOptionalScheme(value, fallbackValue) {
  const normalized = String(value || '').trim().toLowerCase();
  return /^[a-z][a-z0-9+.-]*$/.test(normalized) ? normalized : fallbackValue;
}

function normalizeEasyTierDefaultMode(value) {
  const normalized = String(value || '').trim().toLowerCase();
  if (normalized === 'community' || normalized === 'shared' || normalized === 'shared-community') {
    return 'community';
  }
  return DEFAULT_EASYTIER_DEFAULT_MODE;
}

function normalizeOptionalPath(value) {
  const normalized = String(value || '').trim();
  return normalized ? path.resolve(normalized) : '';
}

function parseStringList(value) {
  const normalized = String(value || '').trim();
  if (!normalized) {
    return [];
  }
  if (normalized.startsWith('[')) {
    try {
      const parsed = JSON.parse(normalized);
      if (Array.isArray(parsed)) {
        return parsed
          .map((item) => String(item || '').trim())
          .filter((item) => item.length > 0);
      }
    } catch (_error) {
    }
  }
  return normalized
    .split(/[;\r\n]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

module.exports = {
  DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
  DEFAULT_OFFLINE_TIMEOUT_SECONDS,
  DEFAULT_QQ_GROUP_NUMBER,
  DEFAULT_EASYTIER_CONFIG_SERVER_SCHEME,
  DEFAULT_EASYTIER_CONFIG_SERVER_PORT,
  DEFAULT_EASYTIER_ENTRY_NODE_SCHEME,
  DEFAULT_EASYTIER_ENTRY_NODE_PORT,
  DEFAULT_EASYTIER_CONNECT_TIMEOUT_SECONDS,
  DEFAULT_EASYTIER_STATUS_POLL_INTERVAL_SECONDS,
  DEFAULT_EASYTIER_DEFAULT_MODE,
  DEFAULT_EASYTIER_SESSION_TTL_SECONDS,
  DEFAULT_EASYTIER_WEB_EMBED_API_SERVER_PORT,
  DEFAULT_EASYTIER_WEB_EMBED_API_SERVER_ADDR,
  DEFAULT_EASYTIER_RUNTIME_DATA_DIR,
  DEFAULT_EASYTIER_SHARED_NODE_INSTANCE_NAME,
  DEFAULT_EASYTIER_SHARED_NODE_HOSTNAME,
  DEFAULT_EASYTIER_SHARED_NODE_NETWORK_NAME,
  DEFAULT_EASYTIER_SHARED_NODE_RPC_PORTAL,
  loadConfig,
  parsePositiveInteger,
  firstNonEmpty,
  parseBoolean,
  normalizeQqGroupNumber,
  parseStringList
};
