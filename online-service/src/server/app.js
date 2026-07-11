'use strict';

const fs = require('fs');
const path = require('path');
const Fastify = require('fastify');
const websocket = require('@fastify/websocket');

const {
  DEFAULT_QQ_GROUP_NUMBER,
  DEFAULT_EASYTIER_CONFIG_SERVER_PORT,
  DEFAULT_EASYTIER_CONFIG_SERVER_SCHEME,
  DEFAULT_EASYTIER_ENTRY_NODE_PORT,
  DEFAULT_EASYTIER_ENTRY_NODE_SCHEME,
  loadConfig,
  firstNonEmpty
} = require('./config');
const { openDatabase } = require('./db');
const { LanStore } = require('./lan');
const { HOUR_MS, PresenceStore, httpError, resolveStatsWindowSeconds } = require('./presence');
const { EasyTierRuntimeManager } = require('./runtime');

const SERVICE_NAME = 'sts-online-service';
const CLIENT_DIR = path.resolve(__dirname, '../client');
const VUE_SCRIPT_PATH = path.join(
  path.dirname(require.resolve('vue/package.json')),
  'dist/vue.global.prod.js'
);
const VUETIFY_SCRIPT_PATH = require.resolve('vuetify/dist/vuetify.min.js');
const VUETIFY_STYLE_PATH = require.resolve('vuetify/dist/vuetify.min.css');
const ECHARTS_SCRIPT_PATH = require.resolve('echarts/dist/echarts.min.js');
const MDI_STYLE_PATH = require.resolve('@mdi/font/css/materialdesignicons.min.css');
const MDI_FONT_DIR = path.resolve(path.dirname(require.resolve('@mdi/font/package.json')), 'fonts');

async function buildServer(config = loadConfig()) {
  const fastify = Fastify({
    logger: config.logLevel === 'silent' ? false : { level: config.logLevel }
  });
  const database = await openDatabase(config.dbPath);
  const store = new PresenceStore(database, config);
  const lanStore = new LanStore(database, config);
  const easyTierRuntime = new EasyTierRuntimeManager(config, fastify.log);
  const panelSockets = new Map();
  const timers = new Set();
  let snapshotBroadcastTimer = null;

  await fastify.register(websocket, {
    options: {
      maxPayload: 32 * 1024
    }
  });

  fastify.get('/', async (_request, _reply) => ({
    ok: true,
    service: SERVICE_NAME,
    now: new Date().toISOString(),
    panel: '/online',
    panelLegacy: '/presence',
    websocket: '/api/presence/ws'
  }));

  fastify.get('/healthz', async (_request, _reply) => ({
    ok: true,
    service: SERVICE_NAME,
    storageBackend: 'sqlite3',
    easyTierRuntime: easyTierRuntime.getHealthSummary()
  }));

  fastify.get('/cloud-control.json', async (request, reply) => {
    reply.header('Cache-Control', 'no-cache');
    return buildCloudControlResponse(config, request);
  });

  fastify.get('/api/presence/config', async (request, reply) => {
    reply.header('Cache-Control', 'no-store');
    const cloudControl = buildCloudControlResponse(config, request);
    return {
      ok: true,
      service: SERVICE_NAME,
      storageBackend: 'sqlite3',
      panelTokenConfigured: Boolean(resolvePresencePanelToken(config)),
      heartbeatIntervalSeconds: config.presenceHeartbeatIntervalSeconds,
      offlineTimeoutSeconds: config.presenceOfflineTimeoutSeconds,
      heartbeatWsUrl: cloudControl.heartbeat.wsUrl,
      easyTier: cloudControl.easyTier,
      easyTierRuntime: easyTierRuntime.getHealthSummary()
    };
  });

  fastify.get('/api/easytier/runtime/status', async (request, reply) => {
    enforcePresencePanelAccess(request, config);
    reply.header('Cache-Control', 'no-store');
    return {
      ok: true,
      ...easyTierRuntime.getStatus()
    };
  });

  fastify.post('/api/easytier/runtime/start', async (request, reply) => {
    enforcePresencePanelAccess(request, config);
    reply.header('Cache-Control', 'no-store');
    return {
      ok: true,
      ...(await easyTierRuntime.start())
    };
  });

  fastify.post('/api/easytier/runtime/stop', async (request, reply) => {
    enforcePresencePanelAccess(request, config);
    reply.header('Cache-Control', 'no-store');
    return {
      ok: true,
      ...(await easyTierRuntime.stop())
    };
  });

  fastify.post('/api/easytier/runtime/restart', async (request, reply) => {
    enforcePresencePanelAccess(request, config);
    reply.header('Cache-Control', 'no-store');
    return {
      ok: true,
      ...(await easyTierRuntime.restart())
    };
  });

  fastify.post('/api/lan/session/start', async (request, reply) => {
    reply.header('Cache-Control', 'no-store');
    const baseUrl = config.publicBaseUrl || buildRequestBaseUrl(config, request);
    return {
      ok: true,
      ...(await lanStore.startSession(request.body || {}, {
        easyTier: buildEasyTierCloudControlResponse(config, baseUrl)
      }))
    };
  });

  fastify.post('/api/lan/rooms', async (request, reply) => {
    reply.header('Cache-Control', 'no-store');
    const baseUrl = config.publicBaseUrl || buildRequestBaseUrl(config, request);
    return {
      ok: true,
      ...(await lanStore.createRoom(request.body || {}, {
        easyTier: buildEasyTierCloudControlResponse(config, baseUrl)
      }))
    };
  });

  fastify.get('/api/lan/rooms', async (request, reply) => {
    reply.header('Cache-Control', 'no-store');
    return {
      ok: true,
      ...(await lanStore.listRooms(request.query || {}))
    };
  });

  fastify.post('/api/lan/rooms/:roomId/action', async (request, reply) => {
    reply.header('Cache-Control', 'no-store');
    return {
      ok: true,
      ...(await lanStore.updateRoom(
        request.params && request.params.roomId,
        request.body || {}
      ))
    };
  });

  fastify.post('/api/lan/session/stop', async (request, reply) => {
    reply.header('Cache-Control', 'no-store');
    return {
      ok: true,
      ...(await lanStore.stopSession(request.body || {}))
    };
  });

  fastify.get('/api/lan/session/status', async (request, reply) => {
    reply.header('Cache-Control', 'no-store');
    return {
      ok: true,
      ...(await lanStore.getSessionStatus(request.query || {}))
    };
  });

  fastify.get('/api/lan/rooms/:roomId', async (request, reply) => {
    reply.header('Cache-Control', 'no-store');
    return {
      ok: true,
      ...(await lanStore.getRoomInfo(request.params && request.params.roomId))
    };
  });

  fastify.post('/api/presence/heartbeat', async (request, reply) => {
    reply.header('Cache-Control', 'no-store');
    const result = await store.recordHeartbeat(request.body || {});
    schedulePanelSnapshotBroadcast();
    return {
      ok: true,
      ...result
    };
  });

  fastify.get('/api/presence/summary', async (request, reply) => {
    reply.header('Cache-Control', 'no-store');
    return {
      ok: true,
      ...(await store.buildSummary(request.query))
    };
  });

  fastify.get('/api/presence/online-count', async (request, reply) => {
    reply.header('Cache-Control', 'no-store');
    return {
      ok: true,
      ...(await store.buildSummary(request.query))
    };
  });

  fastify.get('/api/presence/sessions', async (request, reply) => {
    enforcePresencePanelAccess(request, config);
    reply.header('Cache-Control', 'no-store');
    return {
      ok: true,
      ...(await store.buildSnapshot(request.query))
    };
  });

  fastify.get('/api/presence/stats', async (request, reply) => {
    enforcePresencePanelAccess(request, config);
    reply.header('Cache-Control', 'no-store');
    return {
      ok: true,
      ...(await store.buildStats(request.query))
    };
  });

  fastify.get('/api/presence/ws', { websocket: true }, (connection, request) => {
    const socket = unwrapWebSocket(connection);
    sendJson(socket, {
      type: 'hello',
      ok: true,
      service: SERVICE_NAME,
      heartbeatIntervalSeconds: config.presenceHeartbeatIntervalSeconds,
      offlineTimeoutSeconds: config.presenceOfflineTimeoutSeconds,
      storageBackend: 'sqlite3'
    });

    socket.on('message', async (message) => {
      try {
        const parsed = parseSocketJson(message);
        const payload = parsed && parsed.type === 'presence'
          ? { ...(parsed.payload || {}), ...copyHeartbeatTopLevelFields(parsed) }
          : parsed;
        const result = await store.recordHeartbeat(payload || {});
        sendJson(socket, {
          type: 'presence_ack',
          ok: true,
          ...result
        });
        schedulePanelSnapshotBroadcast();
      } catch (error) {
        sendJson(socket, {
          type: 'error',
          ok: false,
          error: normalizeErrorCode(error),
          message: normalizeErrorMessage(error)
        });
      }
    });
  });

  fastify.get('/api/presence/panel/ws', { websocket: true }, (connection, request) => {
    const socket = unwrapWebSocket(connection);
    try {
      enforcePresencePanelAccess(request, config);
    } catch (error) {
      sendJson(socket, {
        type: 'error',
        ok: false,
        error: normalizeErrorCode(error),
        message: normalizeErrorMessage(error)
      });
      socket.close(error.statusCode === 401 ? 1008 : 1011, normalizeErrorMessage(error).slice(0, 120));
      return;
    }

    panelSockets.set(socket, {
      statsWindowSeconds: resolveStatsWindowSeconds(request.query)
    });
    sendJson(socket, {
      type: 'config',
      ok: true,
      service: SERVICE_NAME,
      storageBackend: 'sqlite3',
      heartbeatIntervalSeconds: config.presenceHeartbeatIntervalSeconds,
      offlineTimeoutSeconds: config.presenceOfflineTimeoutSeconds
    });
    sendPanelSnapshot(socket).catch((error) => fastify.log.warn(error));
    sendPanelStats(socket, request.query).catch((error) => fastify.log.warn(error));

    socket.on('message', async (message) => {
      try {
        const parsed = parseSocketJson(message);
        const type = String(parsed && parsed.type || '').trim();
        if (type === 'refresh' || type === 'refresh_snapshot') {
          await sendPanelSnapshot(socket);
          return;
        }
        if (type === 'refresh_stats') {
          await sendPanelStats(socket, parsed);
          return;
        }
        if (type === 'ping') {
          sendJson(socket, {
            type: 'pong',
            ok: true,
            at: new Date().toISOString()
          });
        }
      } catch (error) {
        sendJson(socket, {
          type: 'error',
          ok: false,
          error: normalizeErrorCode(error),
          message: normalizeErrorMessage(error)
        });
      }
    });
    socket.on('close', () => {
      panelSockets.delete(socket);
    });
    socket.on('error', () => {
      panelSockets.delete(socket);
    });
  });

  fastify.get('/presence', async (_request, reply) => sendClientFile(reply, 'index.html', 'text/html; charset=utf-8'));
  fastify.get('/online', async (_request, reply) => sendClientFile(reply, 'index.html', 'text/html; charset=utf-8'));
  fastify.get('/api/presence/panel', async (_request, reply) => sendClientFile(reply, 'index.html', 'text/html; charset=utf-8'));
  fastify.get('/api/online/panel', async (_request, reply) => sendClientFile(reply, 'index.html', 'text/html; charset=utf-8'));
  fastify.get('/presence/app.js', async (_request, reply) => sendClientFile(reply, 'app.js', 'application/javascript; charset=utf-8'));
  fastify.get('/presence/styles.css', async (_request, reply) => sendClientFile(reply, 'styles.css', 'text/css; charset=utf-8'));
  fastify.get('/favicon.ico', async (_request, reply) => sendLauncherIcon(reply));
  fastify.get('/apple-touch-icon.png', async (_request, reply) => sendLauncherIcon(reply));
  fastify.get('/presence/favicon.ico', async (_request, reply) => sendLauncherIcon(reply));
  fastify.get('/presence/apple-touch-icon.png', async (_request, reply) => sendLauncherIcon(reply));
  fastify.get('/presence/launcher-icon.png', async (_request, reply) => sendLauncherIcon(reply));
  fastify.get('/presence/vue.global.prod.js', async (_request, reply) => {
    reply.header('Cache-Control', 'public, max-age=604800, immutable');
    return sendFile(reply, VUE_SCRIPT_PATH, 'application/javascript; charset=utf-8');
  });
  fastify.get('/presence/vendor/vuetify.min.js', async (_request, reply) => {
    reply.header('Cache-Control', 'public, max-age=604800, immutable');
    return sendFile(reply, VUETIFY_SCRIPT_PATH, 'application/javascript; charset=utf-8');
  });
  fastify.get('/presence/vendor/vuetify.min.css', async (_request, reply) => {
    reply.header('Cache-Control', 'public, max-age=604800, immutable');
    return sendFile(reply, VUETIFY_STYLE_PATH, 'text/css; charset=utf-8');
  });
  fastify.get('/presence/vendor/echarts.min.js', async (_request, reply) => {
    reply.header('Cache-Control', 'public, max-age=604800, immutable');
    return sendFile(reply, ECHARTS_SCRIPT_PATH, 'application/javascript; charset=utf-8');
  });
  fastify.get('/presence/vendor/materialdesignicons.min.css', async (_request, reply) => {
    reply.header('Cache-Control', 'public, max-age=604800, immutable');
    return sendFile(reply, MDI_STYLE_PATH, 'text/css; charset=utf-8');
  });
  fastify.get('/presence/vendor/fonts/:fileName', async (request, reply) => {
    return sendMdiFont(request, reply);
  });
  fastify.get('/presence/fonts/:fileName', async (request, reply) => {
    return sendMdiFont(request, reply);
  });

  function sendMdiFont(request, reply) {
    const fileName = path.basename(String(request.params && request.params.fileName || ''));
    if (!/^materialdesignicons-webfont\.(?:eot|ttf|woff|woff2)$/.test(fileName)) {
      reply.code(404);
      return {
        ok: false,
        error: 'not_found',
        message: 'Font not found'
      };
    }
    reply.header('Cache-Control', 'public, max-age=604800, immutable');
    return sendFile(reply, path.join(MDI_FONT_DIR, fileName), resolveFontContentType(fileName));
  }

  fastify.setErrorHandler((error, _request, reply) => {
    const statusCode = normalizeStatusCode(error);
    reply.status(statusCode).send({
      ok: false,
      error: normalizeErrorCode(error),
      message: normalizeErrorMessage(error)
    });
  });

  const snapshotPushTimer = setInterval(() => {
    broadcastPanelSnapshot().catch((error) => fastify.log.warn(error));
  }, Math.max(1, config.panelSnapshotPushIntervalSeconds) * 1000);
  timers.add(snapshotPushTimer);

  const statsPushTimer = setInterval(() => {
    broadcastPanelStats().catch((error) => fastify.log.warn(error));
  }, Math.max(1, config.panelStatsPushIntervalSeconds) * 1000);
  timers.add(statsPushTimer);

  const firstSnapshotDelayMs = (HOUR_MS - (Date.now() % HOUR_MS)) + 1000;
  const snapshotScheduleTimeout = setTimeout(() => {
    store.recordHourlySnapshot().catch((error) => fastify.log.warn(error));
    const hourlyTimer = setInterval(() => {
      store.recordHourlySnapshot().catch((error) => fastify.log.warn(error));
    }, HOUR_MS);
    timers.add(hourlyTimer);
  }, firstSnapshotDelayMs);
  timers.add(snapshotScheduleTimeout);

  fastify.addHook('onClose', async () => {
    if (snapshotBroadcastTimer) {
      clearTimeout(snapshotBroadcastTimer);
      snapshotBroadcastTimer = null;
    }
    for (const timer of timers) {
      clearInterval(timer);
      clearTimeout(timer);
    }
    timers.clear();
    for (const socket of panelSockets.keys()) {
      try {
        socket.close(1001, 'server stopping');
      } catch (_error) {
      }
    }
    panelSockets.clear();
    await easyTierRuntime.stop();
    await database.close();
  });

  fastify.decorate('easyTierRuntimeManager', easyTierRuntime);
  fastify.decorate('startEasyTierRuntime', async () => easyTierRuntime.start());
  fastify.decorate('stopEasyTierRuntime', async () => easyTierRuntime.stop());

  function schedulePanelSnapshotBroadcast() {
    if (snapshotBroadcastTimer) {
      return;
    }
    snapshotBroadcastTimer = setTimeout(() => {
      snapshotBroadcastTimer = null;
      broadcastPanelSnapshot().catch((error) => fastify.log.warn(error));
    }, 250);
  }

  async function sendPanelSnapshot(socket) {
    sendJson(socket, {
      type: 'snapshot',
      ok: true,
      data: await store.buildSnapshot()
    });
  }

  async function sendPanelStats(socket, query) {
    const windowSeconds = resolveStatsWindowSeconds(query);
    const panelState = panelSockets.get(socket);
    if (panelState) {
      panelState.statsWindowSeconds = windowSeconds;
    }
    sendJson(socket, {
      type: 'stats',
      ok: true,
      data: await store.buildStats({
        bucket_seconds: 3600,
        window_seconds: windowSeconds
      })
    });
  }

  async function broadcastPanelSnapshot() {
    if (panelSockets.size === 0) {
      return;
    }
    const snapshot = await store.buildSnapshot();
    for (const socket of Array.from(panelSockets.keys())) {
      sendJson(socket, {
        type: 'snapshot',
        ok: true,
        data: snapshot
      });
    }
  }

  async function broadcastPanelStats() {
    if (panelSockets.size === 0) {
      return;
    }
    const statsByWindow = new Map();
    for (const [socket, panelState] of Array.from(panelSockets.entries())) {
      const windowSeconds = resolveStatsWindowSeconds(panelState);
      if (!statsByWindow.has(windowSeconds)) {
        statsByWindow.set(windowSeconds, await store.buildStats({
          bucket_seconds: 3600,
          window_seconds: windowSeconds
        }));
      }
      sendJson(socket, {
        type: 'stats',
        ok: true,
        data: statsByWindow.get(windowSeconds)
      });
    }
  }

  return fastify;
}

function buildCloudControlResponse(config, request) {
  const baseUrl = config.publicBaseUrl || buildRequestBaseUrl(config, request);
  const heartbeatWsUrl = toWebSocketUrl(new URL('/api/presence/ws', `${baseUrl}/`).toString());

  return {
    heartbeat: {
      intervalSeconds: config.presenceHeartbeatIntervalSeconds,
      wsUrl: heartbeatWsUrl
    },
    qqGroup: {
      number: firstNonEmpty(config.qqGroupNumber, DEFAULT_QQ_GROUP_NUMBER)
    },
    easyTier: buildEasyTierCloudControlResponse(config, baseUrl)
  };
}

function buildEasyTierCloudControlResponse(config, baseUrl) {
  return {
    enabled: Boolean(config.easyTierEnabled),
    roomApiBaseUrl: firstNonEmpty(
      config.easyTierRoomApiBaseUrl,
      config.easyTierEnabled ? baseUrl : ''
    ),
    webConsoleApiBaseUrl: firstNonEmpty(
      config.easyTierWebConsoleApiBaseUrl,
      config.easyTierEnabled ? baseUrl : ''
    ),
    configServerUrl: firstNonEmpty(
      config.easyTierConfigServerUrl,
      config.easyTierEnabled
        ? buildEasyTierEndpointUrl(
          baseUrl,
          config.easyTierConfigServerScheme || DEFAULT_EASYTIER_CONFIG_SERVER_SCHEME,
          config.easyTierConfigServerPort || DEFAULT_EASYTIER_CONFIG_SERVER_PORT
        )
        : ''
    ),
    entryNodeUrl: firstNonEmpty(
      config.easyTierEntryNodeUrl,
      config.easyTierEnabled
        ? buildEasyTierEndpointUrl(
          baseUrl,
          config.easyTierEntryNodeScheme || DEFAULT_EASYTIER_ENTRY_NODE_SCHEME,
          config.easyTierEntryNodePort || DEFAULT_EASYTIER_ENTRY_NODE_PORT
        )
        : ''
    ),
    connectTimeoutSeconds: config.easyTierConnectTimeoutSeconds,
    statusPollIntervalSeconds: config.easyTierStatusPollIntervalSeconds,
    allowSharedCommunityNetwork: Boolean(config.easyTierAllowSharedCommunityNetwork),
    defaultMode: firstNonEmpty(config.easyTierDefaultMode, 'room')
  };
}

function buildEasyTierEndpointUrl(baseUrl, scheme, port) {
  const host = extractHostname(baseUrl);
  if (!host) {
    return '';
  }
  return `${scheme}://${formatEndpointHost(host)}:${port}`;
}

function extractHostname(baseUrl) {
  try {
    return new URL(baseUrl).hostname || '';
  } catch (_error) {
    return '';
  }
}

function formatEndpointHost(host) {
  return host.includes(':') && !host.startsWith('[') ? `[${host}]` : host;
}

function buildRequestBaseUrl(config, request) {
  const headers = request && request.headers || {};
  const proto = firstHeaderValue(headers['x-forwarded-proto']) ||
    firstHeaderValue(headers['x-forwarded-protocol']) ||
    (request && request.protocol) ||
    'http';
  const host = firstHeaderValue(headers['x-forwarded-host']) ||
    firstHeaderValue(headers.host) ||
    `localhost:${config.port}`;
  return `${String(proto).split(',')[0].trim()}://${String(host).split(',')[0].trim()}`;
}

function toWebSocketUrl(url) {
  return String(url || '').replace(/^https:/i, 'wss:').replace(/^http:/i, 'ws:');
}

function enforcePresencePanelAccess(request, config) {
  const requiredToken = resolvePresencePanelToken(config);
  if (!requiredToken) {
    throw httpError(503, 'Presence panel token not configured');
  }
  const query = request && request.query || {};
  const headers = request && request.headers || {};
  const providedToken = firstNonEmpty(
    headers['x-presence-panel-token'],
    headers['x-feedback-key'],
    query.token,
    query.key
  );
  if (providedToken !== requiredToken) {
    throw httpError(401, 'Invalid presence panel token');
  }
}

function resolvePresencePanelToken(config) {
  return firstNonEmpty(
    config && config.presencePanelToken,
    config && config.sharedSecret
  );
}

function sendClientFile(reply, fileName, contentType) {
  return sendFile(reply, path.join(CLIENT_DIR, fileName), contentType);
}

function sendLauncherIcon(reply) {
  reply.header('Cache-Control', 'public, max-age=604800, immutable');
  return sendClientFile(reply, 'launcher-icon.png', 'image/png');
}

function sendFile(reply, filePath, contentType) {
  reply
    .type(contentType)
    .header('Content-Disposition', 'inline');
  return reply.send(fs.createReadStream(filePath));
}

function unwrapWebSocket(connection) {
  return connection && connection.socket ? connection.socket : connection;
}

function sendJson(socket, value) {
  if (!socket || socket.readyState > 1) {
    return false;
  }
  try {
    socket.send(JSON.stringify(value));
    return true;
  } catch (_error) {
    return false;
  }
}

function parseSocketJson(message) {
  const text = Buffer.isBuffer(message) ? message.toString('utf8') : String(message || '');
  if (!text.trim()) {
    return {};
  }
  const parsed = JSON.parse(text);
  return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
}

function copyHeartbeatTopLevelFields(message) {
  const copied = {};
  for (const key of [
    'client_id',
    'clientId',
    'device_id',
    'deviceId',
    'id_type',
    'idType',
    'state',
    'phase',
    'player_name',
    'playerName',
    'app_version',
    'appVersion',
    'device_model',
    'deviceModel',
    'android_version',
    'androidVersion'
  ]) {
    if (message && Object.prototype.hasOwnProperty.call(message, key)) {
      copied[key] = message[key];
    }
  }
  return copied;
}

function firstHeaderValue(value) {
  if (Array.isArray(value)) {
    return value.length > 0 ? String(value[0] || '').trim() : '';
  }
  return String(value || '').trim();
}

function resolveFontContentType(fileName) {
  const normalized = String(fileName || '').toLowerCase();
  if (normalized.endsWith('.woff2')) {
    return 'font/woff2';
  }
  if (normalized.endsWith('.woff')) {
    return 'font/woff';
  }
  if (normalized.endsWith('.ttf')) {
    return 'font/ttf';
  }
  return 'application/vnd.ms-fontobject';
}

function normalizeStatusCode(error) {
  return error && Number.isInteger(error.statusCode) ? error.statusCode : 500;
}

function normalizeErrorCode(error) {
  const statusCode = normalizeStatusCode(error);
  return statusCode >= 500 ? 'internal_error' : 'bad_request';
}

function normalizeErrorMessage(error) {
  return error && error.message ? String(error.message) : 'Unexpected error';
}

module.exports = {
  SERVICE_NAME,
  buildServer,
  buildCloudControlResponse,
  enforcePresencePanelAccess,
  normalizeErrorCode,
  normalizeErrorMessage,
  normalizeStatusCode
};
