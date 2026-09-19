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
const { LanStore } = require('../src/server/lan');
const { PresenceStore } = require('../src/server/presence');
const { buildComponentSpec } = require('../src/server/runtime');

test('presence config reads qq group number for cloud-control', () => {
  assert.equal(
    loadConfig({ LOG_LEVEL: 'silent', QQ_GROUP_NUMBER: '2233445566' }).qqGroupNumber,
    '2233445566'
  );
  assert.equal(
    loadConfig({ LOG_LEVEL: 'silent', QQ_GROUP_NUMBER: 'not-a-group' }).qqGroupNumber,
    '675545914'
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
  assert.equal(loadConfig({ LOG_LEVEL: 'silent' }).easyTierSessionTtlSeconds, 90);
});

test('LAN state resets on restart while presence records and hourly snapshots persist', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const dbPath = path.join(tmpDir, 'presence.sqlite');
  const config = {
    ...loadConfig({ LOG_LEVEL: 'silent', EASYTIER_ENABLED: 'true' }),
    dbPath,
    publicBaseUrl: 'https://online.example.com',
    presencePanelToken: 'panel-secret',
    logLevel: 'silent'
  };
  let server = await buildServer(config);
  t.after(async () => {
    if (server) {
      await server.close();
    }
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();

  const heartbeat = await server.inject({
    method: 'POST',
    url: '/api/presence/heartbeat',
    payload: { client_id: 'persisted-client', state: 'launcher' }
  });
  assert.equal(heartbeat.statusCode, 200);
  const room = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: { roomId: 'memory-room', playerId: 'owner' }
  });
  assert.equal(room.statusCode, 200);
  assert.equal((await server.inject('/api/lan/rooms')).json().rooms.length, 1);

  await server.close();
  server = null;

  const database = await openDatabase(dbPath);
  const store = new PresenceStore(database, config);
  await store.recordHourlySnapshot(Date.now(), { minUpdateIntervalMs: 0 });
  await database.exec(`
    CREATE TABLE lan_rooms (room_id TEXT PRIMARY KEY);
    CREATE TABLE lan_sessions (session_id TEXT PRIMARY KEY);
  `);
  await database.close();

  server = await buildServer(config);
  await server.ready();
  const roomsAfterRestart = await server.inject('/api/lan/rooms');
  assert.equal(roomsAfterRestart.statusCode, 200);
  assert.deepEqual(roomsAfterRestart.json().rooms, []);

  const summaryAfterRestart = await server.inject('/api/presence/summary');
  assert.equal(summaryAfterRestart.statusCode, 200);
  assert.equal(summaryAfterRestart.json().totalDevices, 1);

  await server.close();
  server = null;
  const verificationDatabase = await openDatabase(dbPath);
  assert.deepEqual(
    await verificationDatabase.all(
      "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'lan_%' ORDER BY name"
    ),
    []
  );
  assert.equal(
    Number((await verificationDatabase.get('SELECT COUNT(*) AS count FROM presence_sessions')).count),
    1
  );
  assert.equal(
    Number((await verificationDatabase.get(
      'SELECT COUNT(*) AS count FROM presence_hourly_snapshots'
    )).count),
    1
  );
  await verificationDatabase.close();
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
      clientVersion: '1.5.1',
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

  const sessionStatus = await server.inject({
    method: 'GET',
    url: `/api/lan/session/status?sessionId=${started.json().sessionId}`,
    headers: { authorization: `Bearer ${started.json().sessionToken}` }
  });
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
    description: '',
    mode: 'room',
    allowNewJoins: true,
    closedAtMs: 0,
    memberCount: 1,
    inGameMemberCount: 0,
    roomState: 'active',
    members: [
      {
        playerId: 'alice',
        displayName: 'Alice',
        role: 'owner',
        online: true,
        gameState: 'online',
        assignedIpv4Cidr: ''
      }
    ]
  });
});

test('lan room runtime report updates session status and room member ip', async (t) => {
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

  const started = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'runtime-room',
      playerId: 'alice',
      displayName: 'Alice'
    }
  });
  assert.equal(started.statusCode, 200);

  const runtime = await server.inject({
    method: 'POST',
    url: '/api/lan/session/runtime',
    headers: { authorization: `Bearer ${started.json().sessionToken}` },
    payload: {
      sessionId: started.json().sessionId,
      assignedIpv4Cidr: '10.144.0.1/24',
      relayServerDescription: 'relay://runtime-room'
    }
  });
  assert.equal(runtime.statusCode, 200);
  assert.deepEqual(runtime.json(), {
    ok: true,
    sessionId: started.json().sessionId,
    roomId: 'runtime-room',
    sessionState: 'connected',
    roomState: 'active',
    peerCount: 1,
    assignedIpv4Cidr: '10.144.0.1/24',
    relayServerDescription: 'relay://runtime-room'
  });

  const roomInfo = await server.inject('/api/lan/rooms/runtime-room');
  assert.equal(roomInfo.statusCode, 200);
  assert.deepEqual(roomInfo.json().members, [
    {
      playerId: 'alice',
      displayName: 'Alice',
      role: 'owner',
      online: true,
      gameState: 'online',
      assignedIpv4Cidr: '10.144.0.1/24'
    }
  ]);
});

test('lan room session start rejects older app versions before creating a room', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({
      LOG_LEVEL: 'silent',
      EASYTIER_ENABLED: 'true',
      EASYTIER_MINIMUM_ONLINE_LOBBY_COMPATIBLE_VERSION: '1.5.1'
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

  const rejected = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'upgrade-required-room',
      playerId: 'alice',
      clientVersion: '1.5.0'
    }
  });
  assert.equal(rejected.statusCode, 426);
  assert.match(rejected.json().message, /虚拟局域网需要客户端版本 1\.5\.1 或更高版本/);
  assert.match(rejected.json().message, /requires app version 1\.5\.1 or newer/);

  const listingAfterReject = await server.inject('/api/lan/rooms?limit=10');
  assert.deepEqual(listingAfterReject.json().rooms, []);

  const supported = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'upgrade-required-room',
      playerId: 'alice',
      clientVersion: '1.5.1-dev1'
    }
  });
  assert.equal(supported.statusCode, 200);

  const cloudControl = await server.inject('/cloud-control.json');
  assert.equal(
    cloudControl.json().easyTier.minimumOnlineLobbyCompatibleVersion,
    '1.5.1'
  );
});

test('lan room assigns stable per-room IPv4 addresses from virtual MAC addresses', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent', EASYTIER_ENABLED: 'true' }),
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

  const owner = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'static-ip-room',
      playerId: 'alice',
      macAddress: '02-aa-bb-cc-dd-01'
    }
  });
  const member = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'static-ip-room',
      playerId: 'bob',
      macAddress: '02:aa:bb:cc:dd:02'
    }
  });
  assert.equal(owner.statusCode, 200);
  assert.equal(member.statusCode, 200);
  assert.equal(owner.json().macAddress, '02:AA:BB:CC:DD:01');
  assert.match(owner.json().assignedIpv4Cidr, /^10\.126\.\d+\.\d+\/24$/);
  assert.notEqual(owner.json().assignedIpv4Cidr, member.json().assignedIpv4Cidr);
  assert.equal(
    owner.json().assignedIpv4Cidr.split('.').slice(0, 3).join('.'),
    member.json().assignedIpv4Cidr.split('.').slice(0, 3).join('.')
  );

  const mismatchedRuntime = await server.inject({
    method: 'POST',
    url: '/api/lan/session/runtime',
    headers: { authorization: `Bearer ${member.json().sessionToken}` },
    payload: {
      sessionId: member.json().sessionId,
      assignedIpv4Cidr: owner.json().assignedIpv4Cidr
    }
  });
  assert.equal(mismatchedRuntime.statusCode, 409);
  assert.match(mismatchedRuntime.json().message, /does not match/);

  const stopped = await server.inject({
    method: 'POST',
    url: '/api/lan/session/stop',
    headers: { authorization: `Bearer ${member.json().sessionToken}` },
    payload: { sessionId: member.json().sessionId }
  });
  assert.equal(stopped.statusCode, 200);

  const rejoined = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    headers: { authorization: `Bearer ${member.json().sessionToken}` },
    payload: {
      roomId: 'static-ip-room',
      playerId: 'bob',
      macAddress: '02:aa:bb:cc:dd:02'
    }
  });
  assert.equal(rejoined.statusCode, 200);
  assert.equal(rejoined.json().assignedIpv4Cidr, member.json().assignedIpv4Cidr);

  const duplicateMac = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'static-ip-room',
      playerId: 'charlie',
      macAddress: '02:aa:bb:cc:dd:02'
    }
  });
  assert.equal(duplicateMac.statusCode, 409);

  const separateRoom = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'separate-static-ip-room',
      playerId: 'diana',
      macAddress: '02:aa:bb:cc:dd:02'
    }
  });
  assert.equal(separateRoom.statusCode, 200);
  assert.match(separateRoom.json().assignedIpv4Cidr, /^10\.126\.\d+\.\d+\/24$/);
});

test('lan game-state report marks active room members as in game', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent', EASYTIER_ENABLED: 'true' }),
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
    payload: { roomId: 'game-state-room', playerId: 'host' }
  });
  assert.equal(started.statusCode, 200);

  const startedPayload = started.json();
  const reported = await server.inject({
    method: 'POST',
    url: '/api/lan/session/game-state',
    headers: { authorization: `Bearer ${startedPayload.sessionToken}` },
    payload: { sessionId: startedPayload.sessionId, gameState: 'game' }
  });
  assert.equal(reported.statusCode, 200);
  assert.equal(reported.json().gameState, 'game');
  assert.equal(reported.json().roomState, 'in_game');

  const room = await server.inject('/api/lan/rooms/game-state-room');
  assert.equal(room.statusCode, 200);
  assert.equal(room.json().roomState, 'in_game');
  assert.equal(room.json().members[0].gameState, 'game');

  const ended = await server.inject({
    method: 'POST',
    url: '/api/lan/session/game-state',
    headers: { authorization: `Bearer ${startedPayload.sessionToken}` },
    payload: { sessionId: startedPayload.sessionId, gameState: 'online' }
  });
  assert.equal(ended.statusCode, 200);
  assert.equal(ended.json().gameState, 'online');
  assert.equal(ended.json().roomState, 'active');
});

test('lan room members expose mod lists reported on join and game launch', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent', EASYTIER_ENABLED: 'true' }),
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
      roomId: 'member-mods-room',
      playerId: 'host',
      mods: [
        { name: 'Together in Spire', workshopId: '2384072973' },
        { name: 'My local patch' },
        { name: 'Together in Spire duplicate', workshopId: '2384072973' },
        { name: 'Ignored invalid workshop id', workshopId: 'not-an-id' }
      ]
    }
  });
  assert.equal(started.statusCode, 200);
  const startedPayload = started.json();

  let room = await server.inject('/api/lan/rooms/member-mods-room');
  assert.equal(room.statusCode, 200);
  assert.deepEqual(room.json().members[0].mods, [
    { name: 'Together in Spire', workshopId: '2384072973' },
    { name: 'My local patch', workshopId: '' },
    { name: 'Ignored invalid workshop id', workshopId: '' }
  ]);

  const updated = await server.inject({
    method: 'POST',
    url: '/api/lan/session/mods',
    headers: { authorization: `Bearer ${startedPayload.sessionToken}` },
    payload: {
      sessionId: startedPayload.sessionId,
      mods: [{ name: 'Downfall', workshopId: '1610056683' }]
    }
  });
  assert.equal(updated.statusCode, 200);
  assert.equal(updated.json().reportedModCount, 1);

  room = await server.inject('/api/lan/rooms/member-mods-room');
  assert.deepEqual(room.json().members[0].mods, [
    { name: 'Downfall', workshopId: '1610056683' }
  ]);
});

test('lan game-state automatically returns to online when its heartbeat expires', async () => {
  const store = new LanStore({ easyTierSessionTtlSeconds: 90 });
  const startedAtMs = 1_000_000;
  const started = await store.startSession(
    { roomId: 'game-state-timeout-room', playerId: 'host' },
    {
      nowMs: startedAtMs,
      easyTier: { enabled: true, entryNodeUrl: 'tcp://relay.example.com:11010' }
    }
  );

  await store.reportSessionGameState({
    sessionId: started.sessionId,
    sessionToken: started.sessionToken,
    gameState: 'game'
  }, { nowMs: startedAtMs });

  const whileFresh = await store.getRoomInfo('game-state-timeout-room', {
    nowMs: startedAtMs + 74_999
  });
  assert.equal(whileFresh.roomState, 'in_game');
  assert.equal(whileFresh.members[0].gameState, 'game');

  const afterTimeout = await store.getRoomInfo('game-state-timeout-room', {
    nowMs: startedAtMs + 75_001
  });
  assert.equal(afterTimeout.roomState, 'active');
  assert.equal(afterTimeout.members[0].gameState, 'online');
});

test('concurrent LAN operations do not contend with presence database writes', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent', EASYTIER_ENABLED: 'true' }),
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

  const owner = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: { roomId: 'concurrent-room', playerId: 'owner' }
  });
  assert.equal(owner.statusCode, 200);

  const memberStarts = await Promise.all(Array.from({ length: 6 }, (_, index) => server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: { roomId: 'concurrent-room', playerId: `member-${index}` }
  })));
  assert.equal(memberStarts.every((response) => response.statusCode === 200), true);

  const sessions = [owner, ...memberStarts].map((response, index) => ({
    ...response.json(),
    assignedIpv4Cidr: `10.144.0.${index + 1}/24`
  }));
  const responses = await Promise.all([
    ...sessions.map((session) => server.inject({
      method: 'POST',
      url: '/api/lan/session/runtime',
      headers: { authorization: `Bearer ${session.sessionToken}` },
      payload: {
        sessionId: session.sessionId,
        assignedIpv4Cidr: session.assignedIpv4Cidr
      }
    })),
    ...Array.from({ length: 20 }, () => server.inject('/api/lan/rooms')),
    ...Array.from({ length: 20 }, (_, index) => server.inject({
      method: 'POST',
      url: '/api/presence/heartbeat',
      payload: { client_id: `concurrent-client-${index}`, state: 'launcher' }
    }))
  ]);
  assert.equal(responses.every((response) => response.statusCode === 200), true);

  const roomInfo = await server.inject('/api/lan/rooms/concurrent-room');
  assert.equal(roomInfo.statusCode, 200);
  assert.equal(roomInfo.json().memberCount, 7);
  const summary = await server.inject('/api/presence/summary');
  assert.equal(summary.statusCode, 200);
  assert.equal(summary.json().totalDevices, 20);
});

test('lan runtime reports renew a session lease and expiry releases its credentials', async () => {
  const lanStore = new LanStore(loadConfig({
    LOG_LEVEL: 'silent',
    EASYTIER_ENABLED: 'true',
    EASYTIER_SESSION_TTL_SECONDS: '1800'
  }));

  const started = await lanStore.startSession({
    roomId: 'lease-room',
    playerId: 'alice',
    displayName: 'Alice'
  }, {
    nowMs: 1_000,
    easyTier: {
      enabled: true,
      entryNodeUrl: 'tcp://online.example.com:11010',
      configServerUrl: 'udp://online.example.com:22020'
    }
  });
  const initialSession = await lanStore.findSession(started.sessionId);
  assert.equal(initialSession.expiresAtMs, 1_801_000);

  await lanStore.reportSessionRuntime({
    sessionId: started.sessionId,
    sessionToken: started.sessionToken,
    assignedIpv4Cidr: '10.144.0.1/24'
  }, { nowMs: 10_000 });
  const renewedSession = await lanStore.findSession(started.sessionId);
  assert.equal(renewedSession.expiresAtMs, 1_810_000);

  await lanStore.expireSessions(1_810_001);
  const expiredSession = await lanStore.findSession(started.sessionId);
  assert.equal(expiredSession, null);
  assert.equal(await lanStore.findRoom('lease-room'), null);
});

test('lan runtime lease removes offline members and then deletes an offline owner room', async () => {
  const lanStore = new LanStore(loadConfig({
    LOG_LEVEL: 'silent',
    EASYTIER_ENABLED: 'true',
    EASYTIER_SESSION_TTL_SECONDS: '90'
  }));
  const easyTier = {
    enabled: true,
    entryNodeUrl: 'tcp://online.example.com:11010',
    configServerUrl: 'udp://online.example.com:22020'
  };
  const owner = await lanStore.startSession({
    roomId: 'offline-room', playerId: 'alice', displayName: 'Alice'
  }, { nowMs: 1_000, easyTier });
  const member = await lanStore.startSession({
    roomId: 'offline-room', playerId: 'bob', displayName: 'Bob'
  }, { nowMs: 2_000, easyTier });

  await lanStore.reportSessionRuntime({
    sessionId: owner.sessionId,
    sessionToken: owner.sessionToken,
    assignedIpv4Cidr: '10.144.0.1/24'
  }, { nowMs: 80_000 });
  await lanStore.reportSessionRuntime({
    sessionId: owner.sessionId,
    sessionToken: owner.sessionToken,
    assignedIpv4Cidr: '10.144.0.1/24'
  }, { nowMs: 160_000 });

  const activeRoom = await lanStore.getRoomInfo('offline-room', { nowMs: 160_000 });
  assert.equal(activeRoom.memberCount, 1);
  assert.deepEqual(activeRoom.members.map((item) => item.playerId), ['alice']);
  const expiredMember = await lanStore.findSession(member.sessionId);
  assert.equal(expiredMember.sessionState, 'expired');
  assert.equal(expiredMember.networkSecret, '');
  assert.equal(expiredMember.endedAtMs, 160_000);

  const rejoinedMember = await lanStore.startSession({
    roomId: 'offline-room', playerId: 'bob', displayName: 'Bob'
  }, { nowMs: 161_000, easyTier });
  assert.notEqual(rejoinedMember.sessionId, member.sessionId);

  await lanStore.expireSessions(250_001);
  assert.equal(await lanStore.findRoom('offline-room'), null);
});

test('lan room limits active sessions from one source network', async () => {
  const lanStore = new LanStore(loadConfig({ LOG_LEVEL: 'silent', EASYTIER_ENABLED: 'true' }));
  const easyTier = {
    enabled: true,
    entryNodeUrl: 'tcp://online.example.com:11010',
    configServerUrl: 'udp://online.example.com:22020'
  };
  await lanStore.startSession({ roomId: 'quota-room', playerId: 'owner' }, {
    nowMs: 1_000,
    sourceIp: '198.51.100.7',
    easyTier
  });
  for (let index = 0; index < 63; index += 1) {
    await lanStore.startSession({ roomId: 'quota-room', playerId: `member-${index}` }, {
      nowMs: 2_000 + index,
      sourceIp: '198.51.100.7',
      easyTier
    });
  }
  await assert.rejects(
    lanStore.startSession({ roomId: 'quota-room', playerId: 'member-overflow' }, {
      nowMs: 3_000,
      sourceIp: '198.51.100.7',
      easyTier
    }),
    (error) => error && error.statusCode === 429
  );
});

test('lan rooms are not limited by the owner source network', async () => {
  const lanStore = new LanStore(loadConfig({ LOG_LEVEL: 'silent', EASYTIER_ENABLED: 'true' }));
  const easyTier = {
    enabled: true,
    entryNodeUrl: 'tcp://online.example.com:11010',
    configServerUrl: 'udp://online.example.com:22020'
  };
  const sourceIp = '198.51.100.7';

  for (let index = 0; index < 11; index += 1) {
    const session = await lanStore.startSession({
      roomId: `shared-network-room-${index}`,
      playerId: `owner-${index}`
    }, {
      nowMs: 1_000 + index,
      sourceIp,
      easyTier
    });
    assert.equal(session.roomId, `shared-network-room-${index}`);
  }

  const rooms = await lanStore.listRooms({}, { nowMs: 2_000 });
  assert.equal(rooms.rooms.length, 11);
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
    headers: { authorization: `Bearer ${first.json().sessionToken}` },
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
  assert.match(second.json().ownerToken, /^[A-Za-z0-9_-]{32,}$/);
  assert.notEqual(second.json().ownerToken, first.json().ownerToken);

  const firstStatus = await server.inject({
    method: 'GET',
    url: `/api/lan/session/status?sessionId=${first.json().sessionId}`,
    headers: { authorization: `Bearer ${first.json().sessionToken}` }
  });
  assert.equal(firstStatus.statusCode, 200);
  assert.equal(firstStatus.json().sessionState, 'superseded');
  assert.equal(firstStatus.json().peerCount, 1);

  const stop = await server.inject({
    method: 'POST',
    url: '/api/lan/session/stop',
    headers: { authorization: `Bearer ${second.json().sessionToken}` },
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

  const secondStatus = await server.inject({
    method: 'GET',
    url: `/api/lan/session/status?sessionId=${second.json().sessionId}`,
    headers: { authorization: `Bearer ${second.json().sessionToken}` }
  });
  assert.equal(secondStatus.statusCode, 404);

  const roomInfo = await server.inject('/api/lan/rooms/reconnect-room');
  assert.equal(roomInfo.statusCode, 404);
});

test('lan room session start creates a joined room and preserves its join policy', async (t) => {
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
    url: '/api/lan/session/start',
    payload: {
      roomId: 'alpha-room',
      playerId: 'alice',
      displayName: 'Alice',
      description: 'Looking for two players for a relaxed run',
      allowNewJoins: false
    }
  });
  assert.equal(created.statusCode, 200);
  assert.equal(created.json().roomId, 'alpha-room');

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
    description: 'Looking for two players for a relaxed run',
    mode: 'room',
    allowNewJoins: false,
    closedAtMs: 0,
    memberCount: 1,
    onlineMemberCount: 1,
    inGameMemberCount: 0,
    roomState: 'locked',
    lastSessionStartedAtMs: alphaRoom.lastSessionStartedAtMs,
    updatedAtMs: alphaRoom.updatedAtMs
  });
  assert.equal(betaRoom.onlineMemberCount, 1);

  const roomInfo = await server.inject('/api/lan/rooms/alpha-room');
  assert.equal(roomInfo.statusCode, 200);
  assert.equal(roomInfo.json().description, 'Looking for two players for a relaxed run');
});

test('lan room create-only rejects an existing room without changing normal joins', async (t) => {
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
    url: '/api/lan/session/start',
    payload: {
      roomId: 'create-only-room',
      playerId: 'alice',
      displayName: 'Alice',
      createOnly: true
    }
  });
  assert.equal(created.statusCode, 200);

  const duplicateCreate = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'create-only-room',
      playerId: 'bob',
      displayName: 'Bob',
      createOnly: true
    }
  });
  assert.equal(duplicateCreate.statusCode, 409);
  assert.match(duplicateCreate.json().message, /room already exists/);

  const normalJoin = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: {
      roomId: 'create-only-room',
      playerId: 'bob',
      displayName: 'Bob'
    }
  });
  assert.equal(normalJoin.statusCode, 200);
  assert.notEqual(normalJoin.json().sessionId, created.json().sessionId);

  const room = await server.inject('/api/lan/rooms/create-only-room');
  assert.equal(room.statusCode, 200);
  assert.equal(room.json().memberCount, 2);
});

test('lan room api rejects ownerless room creation', async (t) => {
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
      roomId: 'conflict-room',
      playerId: 'alice',
      displayName: 'Alice'
    }
  });
  assert.equal(created.statusCode, 409);
  assert.match(created.json().message, /owner starting a session/);

  const listing = await server.inject('/api/lan/rooms?limit=10');
  assert.deepEqual(listing.json().rooms, []);
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
    url: '/api/lan/session/start',
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
    headers: { authorization: `Bearer ${created.json().sessionToken}` },
    payload: {
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
    headers: { 'x-lan-owner-token': created.json().ownerToken },
    payload: {
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

  const memberCannotLock = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms/owner-room/action',
    headers: { authorization: `Bearer ${joined.json().sessionToken}` },
    payload: { action: 'lock' }
  });
  assert.equal(memberCannotLock.statusCode, 403);

  const closed = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms/owner-room/action',
    headers: { 'x-lan-owner-token': created.json().ownerToken },
    payload: {
      action: 'close'
    }
  });
  assert.equal(closed.statusCode, 200);
  assert.equal(closed.json().allowNewJoins, false);
  assert.ok(closed.json().closedAtMs > 0);
  assert.equal(closed.json().memberCount, 0);

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
  assert.equal(joinAfterClose.statusCode, 200);
  assert.equal(joinAfterClose.json().roomId, 'owner-room');

  const unlockAfterClose = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms/owner-room/action',
    headers: { 'x-lan-owner-token': 'A'.repeat(43) },
    payload: {
      action: 'unlock'
    }
  });
  assert.equal(unlockAfterClose.statusCode, 403);

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

test('lan room owner can kick a member with an optional message', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent', EASYTIER_ENABLED: 'true' }),
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

  const owner = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: { roomId: 'kick-room', playerId: 'alice', displayName: 'Alice' }
  });
  const member = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: { roomId: 'kick-room', playerId: 'bob', displayName: 'Bob' }
  });
  assert.equal(owner.statusCode, 200);
  assert.equal(member.statusCode, 200);

  const kicked = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms/kick-room/action',
    headers: { authorization: `Bearer ${owner.json().sessionToken}` },
    payload: {
      action: 'kick',
      targetPlayerId: 'bob',
      message: 'Please update Together in Spire before rejoining.'
    }
  });
  assert.equal(kicked.statusCode, 200);
  assert.equal(kicked.json().kickedPlayerId, 'bob');
  assert.equal(kicked.json().kickedDisplayName, 'Bob');
  assert.equal(kicked.json().kickMessage, 'Please update Together in Spire before rejoining.');
  assert.equal(kicked.json().memberCount, 1);
  assert.deepEqual(kicked.json().members.map((entry) => entry.playerId), ['alice']);

  const memberStatus = await server.inject({
    method: 'GET',
    url: `/api/lan/session/status?sessionId=${member.json().sessionId}`,
    headers: { authorization: `Bearer ${member.json().sessionToken}` }
  });
  assert.equal(memberStatus.statusCode, 200);
  assert.equal(memberStatus.json().sessionState, 'kicked');
  assert.equal(memberStatus.json().kickMessage, 'Please update Together in Spire before rejoining.');
  assert.ok(memberStatus.json().kickedAtMs > 0);

  const cannotKickOwner = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms/kick-room/action',
    headers: { authorization: `Bearer ${owner.json().sessionToken}` },
    payload: { action: 'kick', targetPlayerId: 'alice' }
  });
  assert.equal(cannotKickOwner.statusCode, 400);
  assert.match(cannotKickOwner.json().message, /owner cannot be removed/);

  const alreadyGone = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms/kick-room/action',
    headers: { authorization: `Bearer ${owner.json().sessionToken}` },
    payload: { action: 'kick', targetPlayerId: 'bob' }
  });
  assert.equal(alreadyGone.statusCode, 404);
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
    url: '/api/lan/session/start',
    payload: {
      roomId: 'guard-room',
      playerId: 'alice',
      displayName: 'Alice'
    }
  });

  const response = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms/guard-room/action',
    headers: { 'x-lan-owner-token': 'A'.repeat(43) },
    payload: {
      action: 'lock'
    }
  });
  assert.equal(response.statusCode, 403);
  assert.match(response.json().message, /Only the room owner/);
});

test('lan room credentials prevent player id spoofing and tokenless session access', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent', EASYTIER_ENABLED: 'true' }),
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

  const owner = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: { roomId: 'secure-room', playerId: 'alice', displayName: 'Alice' }
  });
  assert.equal(owner.statusCode, 200);
  assert.match(owner.json().sessionToken, /^[A-Za-z0-9_-]{32,}$/);
  assert.match(owner.json().ownerToken, /^[A-Za-z0-9_-]{32,}$/);

  const spoofedReconnect = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: { roomId: 'secure-room', playerId: 'alice', displayName: 'Mallory' }
  });
  assert.equal(spoofedReconnect.statusCode, 403);

  const spoofedClose = await server.inject({
    method: 'POST',
    url: '/api/lan/rooms/secure-room/action',
    headers: { 'x-lan-owner-token': 'A'.repeat(43) },
    payload: { action: 'close' }
  });
  assert.equal(spoofedClose.statusCode, 403);

  const tokenlessStatus = await server.inject({
    method: 'GET',
    url: `/api/lan/session/status?sessionId=${owner.json().sessionId}`
  });
  assert.equal(tokenlessStatus.statusCode, 400);
  const sessionInfo = await server.inject({
    method: 'GET',
    url: `/api/lan/session/status?sessionId=${owner.json().sessionId}`,
    headers: { authorization: `Bearer ${owner.json().sessionToken}` }
  });
  assert.equal(sessionInfo.statusCode, 200);
});

test('lan room is deleted when its owner leaves', async (t) => {
  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'sts-presence-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent', EASYTIER_ENABLED: 'true' }),
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

  const owner = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: { roomId: 'owner-leaves-room', playerId: 'alice', displayName: 'Alice' }
  });
  const member = await server.inject({
    method: 'POST',
    url: '/api/lan/session/start',
    payload: { roomId: 'owner-leaves-room', playerId: 'bob', displayName: 'Bob' }
  });
  assert.equal(owner.statusCode, 200);
  assert.equal(member.statusCode, 200);

  const left = await server.inject({
    method: 'POST',
    url: '/api/lan/session/stop',
    headers: { authorization: `Bearer ${owner.json().sessionToken}` },
    payload: { sessionId: owner.json().sessionId }
  });
  assert.equal(left.statusCode, 200);

  const room = await server.inject('/api/lan/rooms/owner-leaves-room');
  assert.equal(room.statusCode, 404);
  const memberSession = await server.inject({
    method: 'GET',
    url: `/api/lan/session/status?sessionId=${member.json().sessionId}`,
    headers: { authorization: `Bearer ${member.json().sessionToken}` }
  });
  assert.equal(memberSession.statusCode, 404);
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
      number: '675545914'
    },
    easyTier: {
      enabled: false,
      roomApiBaseUrl: '',
      webConsoleApiBaseUrl: '',
      configServerUrl: '',
      entryNodeUrl: '',
      connectTimeoutSeconds: 12,
      statusPollIntervalSeconds: 5,
      minimumOnlineLobbyCompatibleVersion: '1.5.5',
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
    minimumOnlineLobbyCompatibleVersion: '1.5.5',
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
    ['/presence/runtime-config.js', 'application/javascript'],
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
