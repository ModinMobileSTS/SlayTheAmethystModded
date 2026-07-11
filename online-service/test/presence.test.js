'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const WebSocket = require('ws');

const { buildServer } = require('../src/server/app');
const { loadConfig } = require('../src/server/config');
const { openDatabase } = require('../src/server/db');
const { PresenceStore } = require('../src/server/presence');
const { buildComponentSpec } = require('../src/server/runtime');

test('presence config reads qq group number for cloud-control', () => {
  assert.equal(
    loadConfig({ LOG_LEVEL: 'silent', QQ_GROUP_NUMBER: '2233445566' }).qqGroupNumber,
    '2233445566'
  );
  assert.equal(
    loadConfig({ LOG_LEVEL: 'silent', QQ_GROUP_NUMBER: 'not-a-group' }).qqGroupNumber,
    '1029305387'
  );
});

test('presence config reads easytier single-server cloud-control options', () => {
  const config = loadConfig({
    LOG_LEVEL: 'silent',
    EASYTIER_ENABLED: 'true',
    EASYTIER_ROOM_API_BASE_URL: 'https://online.example.com',
    EASYTIER_WEB_CONSOLE_API_BASE_URL: 'https://online.example.com/console',
    EASYTIER_CONFIG_SERVER_SCHEME: 'udp',
    EASYTIER_CONFIG_SERVER_PORT: '22020',
    EASYTIER_ENTRY_NODE_SCHEME: 'tcp',
    EASYTIER_ENTRY_NODE_PORT: '11010',
    EASYTIER_CONNECT_TIMEOUT_SECONDS: '18',
    EASYTIER_STATUS_POLL_INTERVAL_SECONDS: '7',
    EASYTIER_SESSION_TTL_SECONDS: '2700',
    EASYTIER_ALLOW_SHARED_COMMUNITY_NETWORK: 'yes',
    EASYTIER_DEFAULT_MODE: 'shared'
  });

  assert.equal(config.easyTierEnabled, true);
  assert.equal(config.easyTierRoomApiBaseUrl, 'https://online.example.com');
  assert.equal(config.easyTierWebConsoleApiBaseUrl, 'https://online.example.com/console');
  assert.equal(config.easyTierConfigServerScheme, 'udp');
  assert.equal(config.easyTierConfigServerPort, 22020);
  assert.equal(config.easyTierEntryNodeScheme, 'tcp');
  assert.equal(config.easyTierEntryNodePort, 11010);
  assert.equal(config.easyTierConnectTimeoutSeconds, 18);
  assert.equal(config.easyTierStatusPollIntervalSeconds, 7);
  assert.equal(config.easyTierSessionTtlSeconds, 2700);
  assert.equal(config.easyTierAllowSharedCommunityNetwork, true);
  assert.equal(config.easyTierDefaultMode, 'community');
});

test('presence config reads managed easytier runtime options', () => {
  const config = loadConfig({
    LOG_LEVEL: 'silent',
    EASYTIER_ENABLED: 'true',
    EASYTIER_MANAGED: 'true',
    EASYTIER_MANAGED_AUTO_START: 'true',
    EASYTIER_MANAGED_RESTART_ON_EXIT: 'false',
    EASYTIER_MANAGED_STOP_TIMEOUT_MS: '9000',
    EASYTIER_MANAGED_RESTART_DELAY_MS: '3500',
    EASYTIER_RUNTIME_DATA_DIR: './agent-tmp/runtime-data',
    EASYTIER_WEB_EMBED_BINARY_PATH: './agent-tmp/fake-web.exe',
    EASYTIER_WEB_EMBED_BINARY_ARGS: 'serve;--verbose',
    EASYTIER_WEB_EMBED_API_SERVER_PORT: '12345',
    EASYTIER_WEB_EMBED_API_SERVER_ADDR: '0.0.0.0',
    EASYTIER_WEB_EMBED_DISABLE_WEB: 'false',
    EASYTIER_WEB_EMBED_INTERNAL_AUTH_TOKEN: 'token-123',
    EASYTIER_CORE_BINARY_PATH: './agent-tmp/fake-core.exe',
    EASYTIER_CORE_BINARY_ARGS: '--role;shared-node',
    EASYTIER_CORE_EXTRA_ARGS: '--latency-first;--disable-kcp',
    EASYTIER_SHARED_NODE_NETWORK_NAME: 'test-network',
    EASYTIER_SHARED_NODE_NETWORK_SECRET: 'test-secret',
    EASYTIER_SHARED_NODE_INSTANCE_NAME: 'test-instance',
    EASYTIER_SHARED_NODE_HOSTNAME: 'test-host',
    EASYTIER_SHARED_NODE_RPC_PORTAL: '127.0.0.1:19090',
    EASYTIER_SHARED_NODE_CONFIG_SERVER: 'udp://online.example.com:22020'
  });

  assert.equal(config.easyTierManaged, true);
  assert.equal(config.easyTierManagedAutoStart, true);
  assert.equal(config.easyTierManagedRestartOnExit, false);
  assert.equal(config.easyTierManagedStopTimeoutMs, 9000);
  assert.equal(config.easyTierManagedRestartDelayMs, 3500);
  assert.match(config.easyTierRuntimeDataDir, /agent-tmp[\\/]+runtime-data$/);
  assert.match(config.easyTierWebEmbedBinaryPath, /agent-tmp[\\/]+fake-web\.exe$/);
  assert.deepEqual(config.easyTierWebEmbedBinaryArgs, ['serve', '--verbose']);
  assert.equal(config.easyTierWebEmbedApiServerPort, 12345);
  assert.equal(config.easyTierWebEmbedApiServerAddr, '0.0.0.0');
  assert.equal(config.easyTierWebEmbedDisableWeb, false);
  assert.equal(config.easyTierWebEmbedInternalAuthToken, 'token-123');
  assert.match(config.easyTierCoreBinaryPath, /agent-tmp[\\/]+fake-core\.exe$/);
  assert.deepEqual(config.easyTierCoreBinaryArgs, ['--role', 'shared-node']);
  assert.deepEqual(config.easyTierCoreExtraArgs, ['--latency-first', '--disable-kcp']);
  assert.equal(config.easyTierSharedNodeNetworkName, 'test-network');
  assert.equal(config.easyTierSharedNodeNetworkSecret, 'test-secret');
  assert.equal(config.easyTierSharedNodeInstanceName, 'test-instance');
  assert.equal(config.easyTierSharedNodeHostname, 'test-host');
  assert.equal(config.easyTierSharedNodeRpcPortal, '127.0.0.1:19090');
  assert.equal(config.easyTierSharedNodeConfigServer, 'udp://online.example.com:22020');
});

test('runtime component spec reports missing binaries before launch', () => {
  const config = loadConfig({
    LOG_LEVEL: 'silent',
    EASYTIER_ENABLED: 'true',
    EASYTIER_MANAGED: 'true',
    EASYTIER_WEB_EMBED_BINARY_PATH: './agent-tmp/not-found-web.exe',
    EASYTIER_CORE_BINARY_PATH: './agent-tmp/not-found-core.exe',
    EASYTIER_SHARED_NODE_NETWORK_SECRET: 'test-secret'
  });

  const webEmbedSpec = buildComponentSpec('webEmbed', config);
  const sharedNodeSpec = buildComponentSpec('sharedNode', config);

  assert.equal(webEmbedSpec.configured, false);
  assert.match(webEmbedSpec.reason, /does not exist/);
  assert.equal(sharedNodeSpec.configured, false);
  assert.match(sharedNodeSpec.reason, /does not exist/);
});

test('lan room session api issues status and room members for easytier room mode', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({
      LOG_LEVEL: 'silent',
      EASYTIER_ENABLED: 'true',
      EASYTIER_SESSION_TTL_SECONDS: '1800'
    }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    publicBaseUrl: 'https://online.example.com',
    presencePanelToken: 'panel-secret',
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const started = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'alpha-room',
      playerId: 'alice',
      displayName: 'Alice',
      clientVersion: '1.4.8',
      deviceSummary: 'Pixel 8 sdk35'
    }
  });
  assert.equal(started.statusCode, 200);
  assert.equal(started.json().ok, true);
  assert.equal(started.json().roomId, 'alpha-room');
  assert.equal(started.json().mode, 'room');
  assert.equal(started.json().entryNodeUrl, 'tcp://online.example.com:11010');
  assert.equal(started.json().configServerUrl, 'udp://online.example.com:22020');
  assert.match(started.json().sessionId, /^lan_[a-z0-9]+$/);
  assert.match(started.json().aclGroup, /^room-/);
  assert.ok(started.json().networkSecret.length >= 16);
  assert.ok(Number.isInteger(started.json().expiresAt));

  const sessionStatus = await server.inject(
    `/api/lan/session/status?sessionId=${started.json().sessionId}`
  );
  assert.equal(sessionStatus.statusCode, 200);
  assert.deepEqual(sessionStatus.json(), {
    ok: true,
    sessionId: started.json().sessionId,
    roomId: 'alpha-room',
    sessionState: 'issued',
    roomState: 'active',
    peerCount: 1,
    assignedIpv4Cidr: '',
    relayServerDescription:
      'single-server relay via tcp://online.example.com:11010 (udp://online.example.com:22020)'
  });

  const roomInfo = await server.inject('/api/lan/rooms/alpha-room');
  assert.equal(roomInfo.statusCode, 200);
  assert.deepEqual(roomInfo.json(), {
    ok: true,
    roomId: 'alpha-room',
    ownerPlayerId: 'alice',
    ownerDisplayName: 'Alice',
    mode: 'room',
    allowNewJoins: true,
    closedAtMs: 0,
    memberCount: 1,
    members: [
      {
        playerId: 'alice',
        displayName: 'Alice',
        role: 'owner',
        online: true
      }
    ]
  });
});

test('lan room session api supersedes previous session and supports stop', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({
      LOG_LEVEL: 'silent',
      EASYTIER_ENABLED: 'true'
    }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    publicBaseUrl: 'https://online.example.com',
    presencePanelToken: 'panel-secret',
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const first = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'reconnect-room',
      playerId: 'alice',
      displayName: 'Alice'
    }
  });
  const second = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'reconnect-room',
      playerId: 'alice',
      displayName: 'Alice-2'
    }
  });

  assert.equal(first.statusCode, 200);
  assert.equal(second.statusCode, 200);
  assert.notEqual(first.json().sessionId, second.json().sessionId);
  assert.equal(second.json().roomId, 'reconnect-room');

  const firstStatus = await server.inject(
    `/api/lan/session/status?sessionId=${first.json().sessionId}`
  );
  assert.equal(firstStatus.statusCode, 200);
  assert.equal(firstStatus.json().sessionState, 'superseded');
  assert.equal(firstStatus.json().peerCount, 1);

  const stop = await server.inject({
    method: 'POST',
    url: '/api/lan/session/stop',
    payload: {
      sessionId: second.json().sessionId
    }
  });
  assert.equal(stop.statusCode, 200);
  assert.deepEqual(stop.json(), {
    ok: true,
    sessionId: second.json().sessionId,
    roomId: 'reconnect-room',
    sessionState: 'stopped'
  });

  const secondStatus = await server.inject(
    `/api/lan/session/status?sessionId=${second.json().sessionId}`
  );
  assert.equal(secondStatus.statusCode, 200);
  assert.equal(secondStatus.json().sessionState, 'stopped');
  assert.equal(secondStatus.json().roomState, 'idle');
  assert.equal(secondStatus.json().peerCount, 0);

  const roomInfo = await server.inject('/api/lan/rooms/reconnect-room');
  assert.equal(roomInfo.statusCode, 200);
  assert.equal(roomInfo.json().ownerDisplayName, 'Alice-2');
  assert.deepEqual(roomInfo.json().members, [
    {
      playerId: 'alice',
      displayName: 'Alice-2',
      role: 'owner',
      online: false
    }
  ]);
});

test('lan room api supports explicit room creation and listing', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({
      LOG_LEVEL: 'silent',
      EASYTIER_ENABLED: 'true'
    }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    publicBaseUrl: 'https://online.example.com',
    presencePanelToken: 'panel-secret',
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const created = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms',
    payload: {
      roomId: 'alpha-room',
      playerId: 'alice',
      displayName: 'Alice',
      allowNewJoins: false
    }
  });
  assert.equal(created.statusCode, 200);
  assert.deepEqual(created.json(), {
    ok: true,
    roomId: 'alpha-room',
    ownerPlayerId: 'alice',
    ownerDisplayName: 'Alice',
    mode: 'room',
    allowNewJoins: false,
    closedAtMs: 0,
    memberCount: 1,
    members: [
      {
        playerId: 'alice',
        displayName: 'Alice',
        role: 'owner',
        online: false
      }
    ]
  });

  const started = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'alpha-room',
      playerId: 'alice',
      displayName: 'Alice'
    }
  });
  assert.equal(started.statusCode, 200);

  await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'beta-room',
      playerId: 'bob',
      displayName: 'Bob'
    }
  });

  const listing = await server.inject('/api/lan/rooms?limit=10');
  assert.equal(listing.statusCode, 200);
  assert.equal(listing.json().ok, true);
  assert.equal(listing.json().rooms.length, 2);
  const alphaRoom = listing.json().rooms.find((room) => room.roomId === 'alpha-room');
  const betaRoom = listing.json().rooms.find((room) => room.roomId === 'beta-room');
  assert.ok(alphaRoom);
  assert.ok(betaRoom);
  assert.deepEqual(alphaRoom, {
    roomId: 'alpha-room',
    ownerPlayerId: 'alice',
    ownerDisplayName: 'Alice',
    mode: 'room',
    allowNewJoins: false,
    closedAtMs: 0,
    memberCount: 1,
    onlineMemberCount: 1,
    roomState: 'locked',
    lastSessionStartedAtMs: alphaRoom.lastSessionStartedAtMs,
    updatedAtMs: alphaRoom.updatedAtMs
  });
  assert.equal(betaRoom.onlineMemberCount, 1);
});

test('lan room api rejects conflicting owner on explicit create', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({
      LOG_LEVEL: 'silent',
      EASYTIER_ENABLED: 'true'
    }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    publicBaseUrl: 'https://online.example.com',
    presencePanelToken: 'panel-secret',
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const first = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms',
    payload: {
      roomId: 'conflict-room',
      playerId: 'alice',
      displayName: 'Alice'
    }
  });
  assert.equal(first.statusCode, 200);

  const second = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms',
    payload: {
      roomId: 'conflict-room',
      playerId: 'bob',
      displayName: 'Bob'
    }
  });
  assert.equal(second.statusCode, 409);
  assert.match(second.json().message, /already exists/);
});

test('lan room api supports owner lock unlock and close lifecycle', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({
      LOG_LEVEL: 'silent',
      EASYTIER_ENABLED: 'true'
    }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    publicBaseUrl: 'https://online.example.com',
    presencePanelToken: 'panel-secret',
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const created = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms',
    payload: {
      roomId: 'owner-room',
      playerId: 'alice',
      displayName: 'Alice'
    }
  });
  assert.equal(created.statusCode, 200);

  const locked = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms/owner-room/action',
    payload: {
      playerId: 'alice',
      action: 'lock'
    }
  });
  assert.equal(locked.statusCode, 200);
  assert.equal(locked.json().allowNewJoins, false);
  assert.equal(locked.json().closedAtMs, 0);

  const joinBlocked = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'owner-room',
      playerId: 'bob',
      displayName: 'Bob'
    }
  });
  assert.equal(joinBlocked.statusCode, 403);
  assert.match(joinBlocked.json().message, /not accepting new joins/);

  const unlocked = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms/owner-room/action',
    payload: {
      playerId: 'alice',
      action: 'unlock'
    }
  });
  assert.equal(unlocked.statusCode, 200);
  assert.equal(unlocked.json().allowNewJoins, true);

  const joined = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'owner-room',
      playerId: 'bob',
      displayName: 'Bob'
    }
  });
  assert.equal(joined.statusCode, 200);

  const closed = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms/owner-room/action',
    payload: {
      playerId: 'alice',
      action: 'close'
    }
  });
  assert.equal(closed.statusCode, 200);
  assert.equal(closed.json().allowNewJoins, false);
  assert.ok(closed.json().closedAtMs > 0);
  assert.equal(closed.json().memberCount, 2);

  const afterCloseInfo = await server.inject('/api/lan/rooms/owner-room');
  assert.equal(afterCloseInfo.statusCode, 404);

  const closedListing = await server.inject('/api/lan/rooms?limit=10');
  assert.equal(closedListing.statusCode, 200);
  const ownerRoom = closedListing.json().rooms.find((room) => room.roomId === 'owner-room');
  assert.equal(ownerRoom, undefined);

  const joinAfterClose = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'owner-room',
      playerId: 'charlie',
      displayName: 'Charlie'
    }
  });
  assert.equal(joinAfterClose.statusCode, 403);
  assert.match(joinAfterClose.json().message, /has been closed/);

  const unlockAfterClose = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms/owner-room/action',
    payload: {
      playerId: 'alice',
      action: 'unlock'
    }
  });
  assert.equal(unlockAfterClose.statusCode, 410);

  const recreated = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms',
    payload: {
      roomId: 'owner-room',
      playerId: 'alice',
      displayName: 'Alice',
      allowNewJoins: true
    }
  });
  assert.equal(recreated.statusCode, 200);
  assert.equal(recreated.json().closedAtMs, 0);
  assert.equal(recreated.json().memberCount, 1);
  assert.deepEqual(recreated.json().members, [
    {
      playerId: 'alice',
      displayName: 'Alice',
      role: 'owner',
      online: false
    }
  ]);

  const restarted = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'owner-room',
      playerId: 'alice',
      displayName: 'Alice'
    }
  });
  assert.equal(restarted.statusCode, 200);
  assert.match(restarted.json().aclGroup, /^room-/);
  assert.ok(restarted.json().networkSecret.length >= 16);
  assert.notEqual(restarted.json().networkSecret, joined.json().networkSecret);
});

test('lan room api blocks non-owner room mutations', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({
      LOG_LEVEL: 'silent',
      EASYTIER_ENABLED: 'true'
    }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    publicBaseUrl: 'https://online.example.com',
    presencePanelToken: 'panel-secret',
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  await server.inject({
    method: 'POST',
    url: '/api/lan/rooms',
    payload: {
      roomId: 'guard-room',
      playerId: 'alice',
      displayName: 'Alice'
    }
  });

  const response = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms/guard-room/action',
    payload: {
      playerId: 'bob',
      action: 'lock'
    }
  });
  assert.equal(response.statusCode, 403);
  assert.match(response.json().message, /Only the room owner/);
});

test('lan room session api returns service unavailable when easytier is disabled', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent' }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    publicBaseUrl: 'https://online.example.com',
    presencePanelToken: 'panel-secret',
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const started = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'disabled-room',
      playerId: 'alice'
    }
  });
  assert.equal(started.statusCode, 503);
  assert.equal(started.json().ok, false);
  assert.match(started.json().message, /EasyTier cloud-control is disabled/);
});

test('presence service records heartbeat and returns summary/sessions/stats', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent' }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    presencePanelToken: 'panel-secret',
    presenceHeartbeatIntervalSeconds: 30,
    presenceOfflineTimeoutSeconds: 90,
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const heartbeat = await server.inject({
    method: 'POST',
    url: '/api/presence/heartbeat',
    payload: {
      client_id: 'client-a',
      device_id: 'device-a',
      id_type: 'test',
      state: 'game',
      player_name: 'Ironclad',
      app_version: '1.2.3',
      device_model: 'Google Pixel 8',
      android_version: 'Android 15 (SDK 35)'
    }
  });
  assert.equal(heartbeat.statusCode, 200);
  assert.equal(heartbeat.json().online, 1);
  assert.equal(heartbeat.json().storageBackend, 'sqlite3');

  const summary = await server.inject('/api/presence/summary');
  assert.equal(summary.statusCode, 200);
  assert.equal(summary.json().online, 1);
  assert.equal(summary.json().totalDevices, 1);
  assert.equal(summary.json().totalOnlineUsers, 1);
  assert.deepEqual(summary.json().byState, { game: 1 });

  const unauthorized = await server.inject('/api/presence/sessions');
  assert.equal(unauthorized.statusCode, 401);

  const sessions = await server.inject('/api/presence/sessions?token=panel-secret');
  assert.equal(sessions.statusCode, 200);
  assert.equal(sessions.json().sessions.length, 1);
  assert.equal(sessions.json().sessions[0].playerName, 'Ironclad');
  assert.equal(sessions.json().sessions[0].deviceModel, 'Google Pixel 8');
  assert.equal(sessions.json().sessions[0].androidVersion, 'Android 15 (SDK 35)');

  const stats = await server.inject(
    '/api/presence/stats?token=panel-secret&bucket_seconds=3600&window_seconds=86400'
  );
  assert.equal(stats.statusCode, 200);
  assert.equal(stats.json().currentOnline, 1);
  assert.equal(stats.json().totalOnlineUsers, 1);
  assert.equal(stats.json().windowSeconds, 86400);
  assert.equal(stats.json().bucketSeconds, 3600);
  assert.equal(stats.json().buckets.length, 24);
  assert.ok(Array.isArray(stats.json().buckets));
});

test('presence stats trend uses hourly snapshots instead of live online count', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const database = await openDatabase(path.join(tmpDir, 'presence.sqlite'));
  const store = new PresenceStore(database, {
    presenceHeartbeatIntervalSeconds: 30,
    presenceOfflineTimeoutSeconds: 90
  });
  t.after(async () => {
    await database.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  const baseMs = Date.UTC(2026, 0, 1, 10, 0, 0);
  await store.recordHourlySnapshot(baseMs, { minUpdateIntervalMs: 0 });
  await store.recordHeartbeat({
    client_id: 'client-live',
    state: 'game'
  }, baseMs + 60000);

  const stats = await store.buildStats({
    bucket_seconds: 3600,
    window_seconds: 24 * 60 * 60
  }, baseMs + 60000);
  const currentBucket = stats.buckets[stats.buckets.length - 1];

  assert.equal(stats.windowSeconds, 24 * 60 * 60);
  assert.equal(stats.buckets.length, 24);
  assert.equal(stats.currentOnline, 1);
  assert.equal(stats.peakOnline, 0);
  assert.equal(currentBucket.hasSnapshot, true);
  assert.equal(currentBucket.bucketStart, new Date(baseMs).toISOString());
  assert.equal(currentBucket.online, 0);
});

test('presence snapshot includes online, current-day, and historical distributions for panel pie chart switching', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const database = await openDatabase(path.join(tmpDir, 'presence.sqlite'));
  const store = new PresenceStore(database, {
    presenceHeartbeatIntervalSeconds: 30,
    presenceOfflineTimeoutSeconds: 90
  });
  t.after(async () => {
    await database.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });

  const baseMs = Date.UTC(2026, 0, 1, 10, 0, 0);
  await store.recordHeartbeat({
    client_id: 'client-previous-day',
    state: 'game',
    app_version: '1.1.0',
    device_model: 'OnePlus 11',
    android_version: 'Android 13 (SDK 33)'
  }, baseMs - (20 * 60 * 60 * 1000));
  await store.recordHeartbeat({
    client_id: 'client-offline-history',
    state: 'game',
    app_version: '1.2.0',
    device_model: 'Samsung SM-S9280',
    android_version: 'Android 14 (SDK 34)'
  }, baseMs - (10 * 60 * 1000));
  await store.recordHeartbeat({
    client_id: 'client-online',
    state: 'game',
    app_version: '1.3.0',
    device_model: 'Google Pixel 8',
    android_version: 'Android 15 (SDK 35)'
  }, baseMs);

  const snapshot = await store.buildSnapshot(null, baseMs);

  assert.equal(snapshot.online, 1);
  assert.equal(snapshot.sessions.length, 1);
  assert.equal(snapshot.sessions[0].clientId, 'client-online');
  assert.equal(snapshot.todayDistribution.total, 2);
  assert.deepEqual(snapshot.todayDistribution.deviceModels, [
    { name: 'Google Pixel 8', value: 1 },
    { name: 'Samsung SM-S9280', value: 1 }
  ]);
  assert.deepEqual(snapshot.todayDistribution.appVersions, [
    { name: '1.2.0', value: 1 },
    { name: '1.3.0', value: 1 }
  ]);
  assert.deepEqual(snapshot.todayDistribution.androidVersions, [
    { name: 'Android 14 (SDK 34)', value: 1 },
    { name: 'Android 15 (SDK 35)', value: 1 }
  ]);
  assert.equal(snapshot.historicalDistribution.total, 3);
  assert.deepEqual(snapshot.historicalDistribution.deviceModels, [
    { name: 'Google Pixel 8', value: 1 },
    { name: 'OnePlus 11', value: 1 },
    { name: 'Samsung SM-S9280', value: 1 }
  ]);
  assert.deepEqual(snapshot.historicalDistribution.appVersions, [
    { name: '1.1.0', value: 1 },
    { name: '1.2.0', value: 1 },
    { name: '1.3.0', value: 1 }
  ]);
  assert.deepEqual(snapshot.historicalDistribution.androidVersions, [
    { name: 'Android 13 (SDK 33)', value: 1 },
    { name: 'Android 14 (SDK 34)', value: 1 },
    { name: 'Android 15 (SDK 35)', value: 1 }
  ]);
});

test('cloud-control exposes websocket heartbeat settings', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent' }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    publicBaseUrl: 'https://presence.example.com',
    presencePanelToken: 'panel-secret',
    presenceHeartbeatIntervalSeconds: 30,
    presenceOfflineTimeoutSeconds: 90,
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const response = await server.inject('/cloud-control.json');
  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), {
    heartbeat: {
      intervalSeconds: 30,
      wsUrl: 'wss://presence.example.com/api/presence/ws'
    },
    qqGroup: {
      number: '1029305387'
    },
    easyTier: {
      enabled: false,
      roomApiBaseUrl: '',
      webConsoleApiBaseUrl: '',
      configServerUrl: '',
      entryNodeUrl: '',
      connectTimeoutSeconds: 12,
      statusPollIntervalSeconds: 5,
      allowSharedCommunityNetwork: false,
      defaultMode: 'room'
    }
  });
});

test('cloud-control derives easytier single-server addresses from public base url', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent', EASYTIER_ENABLED: 'true' }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    publicBaseUrl: 'https://online.example.com',
    presencePanelToken: 'panel-secret',
    presenceHeartbeatIntervalSeconds: 30,
    presenceOfflineTimeoutSeconds: 90,
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const response = await server.inject('/cloud-control.json');
  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json().easyTier, {
    enabled: true,
    roomApiBaseUrl: 'https://online.example.com',
    webConsoleApiBaseUrl: 'https://online.example.com',
    configServerUrl: 'udp://online.example.com:22020',
    entryNodeUrl: 'tcp://online.example.com:11010',
    connectTimeoutSeconds: 12,
    statusPollIntervalSeconds: 5,
    allowSharedCommunityNetwork: false,
    defaultMode: 'room'
  });
});

test('runtime status api reports unmanaged or missing binary state without starting child processes', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({
      LOG_LEVEL: 'silent',
      EASYTIER_ENABLED: 'true',
      EASYTIER_MANAGED: 'true',
      EASYTIER_WEB_EMBED_BINARY_PATH: './agent-tmp/missing-web.exe',
      EASYTIER_CORE_BINARY_PATH: './agent-tmp/missing-core.exe',
      EASYTIER_SHARED_NODE_NETWORK_SECRET: 'panel-secret-value'
    }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    presencePanelToken: 'panel-secret',
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const unauthorized = await server.inject('/api/easytier/runtime/status');
  assert.equal(unauthorized.statusCode, 401);

  const response = await server.inject('/api/easytier/runtime/status?token=panel-secret');
  assert.equal(response.statusCode, 200);
  assert.equal(response.json().ok, true);
  assert.equal(response.json().managed, true);
  assert.equal(response.json().enabled, true);
  assert.equal(response.json().desiredState, 'stopped');
  assert.equal(response.json().summary.runningCount, 0);
  assert.equal(response.json().summary.configuredCount, 0);
  assert.equal(response.json().components.webEmbed.running, false);
  assert.equal(response.json().components.sharedNode.running, false);
  assert.match(response.json().components.webEmbed.lastErrorMessage, /does not exist/);
  assert.match(response.json().components.sharedNode.lastErrorMessage, /does not exist/);
});

test('runtime start stop restart api works with local fake child processes', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const fakeBinDir = path.join(tmpDir, 'fake-bin');
  const markerDir = path.join(tmpDir, 'markers');
  fs.mkdirSync(fakeBinDir, { recursive: true });
  fs.mkdirSync(markerDir, { recursive: true });

  const fakeRuntimeScriptPath = path.join(fakeBinDir, 'fake-easytier-runtime.js');
  fs.writeFileSync(fakeRuntimeScriptPath, [
    "'use strict';",
    "const fs = require('node:fs');",
    "const markerPath = process.argv[3];",
    "if (markerPath) {",
    "  fs.appendFileSync(markerPath, `started:${process.argv[2]}\\n`);",
    "}",
    "process.stdout.write(`started:${process.argv.slice(2).join(' ')}\\n`);",
    "const heartbeat = setInterval(() => process.stdout.write('tick\\n'), 200);",
    "function shutdown(signal) {",
    "  clearInterval(heartbeat);",
    "  process.stdout.write(`stopping:${signal}\\n`);",
    "  setTimeout(() => process.exit(0), 50);",
    "}",
    "process.on('SIGTERM', () => shutdown('SIGTERM'));",
    "process.on('SIGINT', () => shutdown('SIGINT'));",
    "setInterval(() => {}, 1000);"
  ].join('\n'));

  const nodeExecutable = process.execPath;
  const server = await buildServer({
    ...loadConfig({
      LOG_LEVEL: 'silent',
      EASYTIER_ENABLED: 'true',
      EASYTIER_MANAGED: 'true',
      EASYTIER_MANAGED_RESTART_ON_EXIT: 'false',
      EASYTIER_RUNTIME_DATA_DIR: path.join(tmpDir, 'runtime-data'),
      EASYTIER_WEB_EMBED_BINARY_PATH: nodeExecutable,
      EASYTIER_WEB_EMBED_BINARY_ARGS: JSON.stringify([
        fakeRuntimeScriptPath,
        'web-embed',
        path.join(markerDir, 'web-embed.txt')
      ]),
      EASYTIER_CORE_BINARY_PATH: nodeExecutable,
      EASYTIER_CORE_BINARY_ARGS: JSON.stringify([
        fakeRuntimeScriptPath,
        'shared-node',
        path.join(markerDir, 'shared-node.txt')
      ]),
      EASYTIER_SHARED_NODE_NETWORK_SECRET: 'test-secret'
    }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    presencePanelToken: 'panel-secret',
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const startResponse = await server.inject({
    method: 'POST',
    url: '/api/easytier/runtime/start?token=panel-secret'
  });
  assert.equal(startResponse.statusCode, 200);
  assert.equal(startResponse.json().ok, true);
  assert.equal(startResponse.json().desiredState, 'running');
  assert.equal(startResponse.json().summary.runningCount, 2);
  assert.equal(startResponse.json().summary.configuredCount, 2);
  assert.equal(startResponse.json().components.webEmbed.running, true);
  assert.equal(startResponse.json().components.sharedNode.running, true);
  assert.ok(startResponse.json().components.webEmbed.pid > 0);
  assert.ok(startResponse.json().components.sharedNode.pid > 0);
  assert.match(startResponse.json().components.sharedNode.args.join(' '), /<redacted>/);

  const statusWhileRunning = await server.inject('/api/easytier/runtime/status?token=panel-secret');
  assert.equal(statusWhileRunning.statusCode, 200);
  assert.equal(statusWhileRunning.json().summary.runningCount, 2);
  assert.equal(statusWhileRunning.json().components.webEmbed.running, true);
  assert.equal(statusWhileRunning.json().components.sharedNode.running, true);

  const webEmbedMarkerPath = path.join(markerDir, 'web-embed.txt');
  const sharedNodeMarkerPath = path.join(markerDir, 'shared-node.txt');
  await waitForCondition(() => fs.existsSync(webEmbedMarkerPath));
  await waitForCondition(() => fs.existsSync(sharedNodeMarkerPath));

  const restartResponse = await server.inject({
    method: 'POST',
    url: '/api/easytier/runtime/restart?token=panel-secret'
  });
  assert.equal(restartResponse.statusCode, 200);
  assert.equal(restartResponse.json().ok, true);
  assert.equal(restartResponse.json().desiredState, 'running');
  assert.equal(restartResponse.json().summary.runningCount, 2);

  const stopResponse = await server.inject({
    method: 'POST',
    url: '/api/easytier/runtime/stop?token=panel-secret'
  });
  assert.equal(stopResponse.statusCode, 200);
  assert.equal(stopResponse.json().ok, true);
  assert.equal(stopResponse.json().desiredState, 'stopped');
  assert.equal(stopResponse.json().summary.runningCount, 0);
  assert.equal(stopResponse.json().components.webEmbed.running, false);
  assert.equal(stopResponse.json().components.sharedNode.running, false);

  const webEmbedStdoutPath = path.join(tmpDir, 'runtime-data', 'logs', 'easytier-web-embed.stdout.log');
  const sharedNodeStdoutPath = path.join(tmpDir, 'runtime-data', 'logs', 'easytier-core.stdout.log');
  assert.match(fs.readFileSync(webEmbedMarkerPath, 'utf8'), /started:web-embed/);
  assert.match(fs.readFileSync(sharedNodeMarkerPath, 'utf8'), /started:shared-node/);
  assert.equal(fs.existsSync(webEmbedStdoutPath), true);
  assert.equal(fs.existsSync(sharedNodeStdoutPath), true);
});

test('runtime options prefer server configuration over request overrides', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent' }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    presencePanelToken: 'panel-secret',
    presenceHeartbeatIntervalSeconds: 30,
    presenceOfflineTimeoutSeconds: 90,
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const heartbeat = await server.inject({
    method: 'POST',
    url: '/api/presence/heartbeat',
    payload: {
      client_id: 'client-override',
      heartbeat_interval_seconds: 600,
      offline_timeout_seconds: 1500
    }
  });
  assert.equal(heartbeat.statusCode, 200);
  assert.equal(heartbeat.json().heartbeatIntervalSeconds, 30);
  assert.equal(heartbeat.json().offlineTimeoutSeconds, 90);

  const summary = await server.inject(
    '/api/presence/summary?heartbeat_interval_seconds=600&offline_timeout_seconds=1500'
  );
  assert.equal(summary.statusCode, 200);
  assert.equal(summary.json().heartbeatIntervalSeconds, 30);
  assert.equal(summary.json().offlineTimeoutSeconds, 90);
});

test('presence panel serves local frontend vendor assets', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent' }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    presencePanelToken: 'panel-secret',
    presenceHeartbeatIntervalSeconds: 30,
    presenceOfflineTimeoutSeconds: 90,
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const paths = [
    ['/favicon.ico', 'image/png'],
    ['/apple-touch-icon.png', 'image/png'],
    ['/presence', 'text/html'],
    ['/online', 'text/html'],
    ['/api/online/panel', 'text/html'],
    ['/presence/app.js', 'application/javascript'],
    ['/presence/styles.css', 'text/css'],
    ['/presence/favicon.ico', 'image/png'],
    ['/presence/apple-touch-icon.png', 'image/png'],
    ['/presence/launcher-icon.png', 'image/png'],
    ['/presence/vue.global.prod.js', 'application/javascript'],
    ['/presence/vendor/vuetify.min.css', 'text/css'],
    ['/presence/vendor/vuetify.min.js', 'application/javascript'],
    ['/presence/vendor/echarts.min.js', 'application/javascript'],
    ['/presence/vendor/materialdesignicons.min.css', 'text/css'],
    ['/presence/fonts/materialdesignicons-webfont.woff2?v=7.4.47', 'font/woff2']
  ];

  for (const [url, contentType] of paths) {
    const response = await server.inject(url);
    assert.equal(response.statusCode, 200, url);
    assert.match(response.headers['content-type'], new RegExp(contentType), url);
  }
});

test('presence websocket accepts status frames', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  let ws = null;
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent' }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    presencePanelToken: 'panel-secret',
    presenceHeartbeatIntervalSeconds: 30,
    presenceOfflineTimeoutSeconds: 90,
    logLevel: 'silent'
  });
  t.after(async () => {
    if (ws) {
      ws.close();
    }
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.listen({ host: '127.0.0.1', port: 0 });
  const address = server.server.address();
  ws = new WebSocket(`ws://127.0.0.1:${address.port}/api/presence/ws`);

  await waitForSocketOpen(ws);
  ws.send(JSON.stringify({
    type: 'presence',
    client_id: 'client-ws',
    device_id: 'device-ws',
    id_type: 'test',
    state: 'game',
    player_name: 'Silent',
    app_version: '1.2.3',
    device_model: 'Samsung SM-S9280',
    android_version: 'Android 14 (SDK 34)'
  }));

  const ack = await waitForSocketMessage(ws, (message) => message.type === 'presence_ack');
  assert.equal(ack.ok, true);
  assert.equal(ack.online, 1);
  assert.equal(ack.totalOnlineUsers, 1);
  assert.equal(ack.storageBackend, 'sqlite3');

  const summary = await server.inject('/api/presence/summary');
  assert.equal(summary.statusCode, 200);
  assert.equal(summary.json().online, 1);

  const sessions = await server.inject('/api/presence/sessions?token=panel-secret');
  assert.equal(sessions.statusCode, 200);
  assert.equal(sessions.json().sessions[0].deviceModel, 'Samsung SM-S9280');
  assert.equal(sessions.json().sessions[0].androidVersion, 'Android 14 (SDK 34)');

  ws.send(JSON.stringify({
    type: 'presence',
    client_id: 'client-ws',
    state: 'launcher',
    sent_at: Date.now()
  }));

  const minimalAck = await waitForSocketMessage(ws, (message) => message.type === 'presence_ack');
  assert.equal(minimalAck.ok, true);

  const sessionsAfterMinimalHeartbeat = await server.inject('/api/presence/sessions?token=panel-secret');
  assert.equal(sessionsAfterMinimalHeartbeat.statusCode, 200);
  assert.equal(sessionsAfterMinimalHeartbeat.json().sessions[0].state, 'launcher');
  assert.equal(sessionsAfterMinimalHeartbeat.json().sessions[0].playerName, 'Silent');
  assert.equal(sessionsAfterMinimalHeartbeat.json().sessions[0].appVersion, '1.2.3');
  assert.equal(sessionsAfterMinimalHeartbeat.json().sessions[0].deviceModel, 'Samsung SM-S9280');
  assert.equal(sessionsAfterMinimalHeartbeat.json().sessions[0].androidVersion, 'Android 14 (SDK 34)');
});

function waitForSocketOpen(ws) {
  return new Promise((resolve, reject) => {
    ws.once('open', resolve);
    ws.once('error', reject);
  });
}

function waitForSocketMessage(ws, predicate) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      cleanup();
      reject(new Error('Timed out waiting for websocket message'));
    }, 5000);

    function cleanup() {
      clearTimeout(timeout);
      ws.off('message', onMessage);
      ws.off('error', onError);
    }

    function onError(error) {
      cleanup();
      reject(error);
    }

    function onMessage(rawMessage) {
      const message = JSON.parse(rawMessage.toString('utf8'));
      if (predicate(message)) {
        cleanup();
        resolve(message);
      }
    }

    ws.on('message', onMessage);
    ws.once('error', onError);
  });
}

function waitForCondition(predicate, timeoutMs = 5000, intervalMs = 50) {
  return new Promise((resolve, reject) => {
    const startedAt = Date.now();
    function check() {
      try {
        if (predicate()) {
          resolve();
          return;
        }
      } catch (error) {
        reject(error);
        return;
      }
      if ((Date.now() - startedAt) >= timeoutMs) {
        reject(new Error('Timed out waiting for condition'));
        return;
      }
      setTimeout(check, intervalMs);
    }
    check();
  });
}
