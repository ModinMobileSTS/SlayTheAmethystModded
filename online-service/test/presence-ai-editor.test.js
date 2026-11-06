'use strict';

const assert = require('node:assert/strict');
const { once } = require('node:events');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const WebSocket = require('ws');

const { buildServer } = require('../src/server/app');
const { loadConfig } = require('../src/server/config');

async function createServer(t) {
  const artifactDir = path.join(__dirname, '../../agent-tmp');
  fs.mkdirSync(artifactDir, { recursive: true });
  const tmpDir = fs.mkdtempSync(path.join(artifactDir, 'presence-ai-editor-'));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent' }),
    dbPath: path.join(tmpDir, 'presence.sqlite'),
    presencePanelToken: 'panel-test-token',
    logLevel: 'silent'
  });
  t.after(async () => {
    await server.close();
    fs.rmSync(tmpDir, { recursive: true, force: true });
  });
  await server.ready();
  return server;
}

const fullHeartbeat = {
  type: 'presence',
  client_id: 'ai-editor-player',
  device_id: 'ai-editor-device',
  id_type: 'test',
  player_name: 'Mod author',
  app_version: '1.6.5',
  device_model: 'Pixel 8',
  android_version: 'Android 15'
};

async function assertPanelState(server, expectedState) {
  const summary = await server.inject('/api/presence/summary');
  assert.equal(summary.statusCode, 200);
  assert.equal(summary.json().online, 1);
  assert.equal(summary.json().totalOnlineUsers, 1);
  assert.deepEqual(summary.json().byState, { [expectedState]: 1 });
  const sessions = await server.inject('/api/presence/sessions?token=panel-test-token');
  assert.equal(sessions.statusCode, 200);
  assert.equal(sessions.json().sessions.length, 1);
  const session = sessions.json().sessions[0];
  assert.equal(session.state, expectedState);
  assert.equal(session.playerName, fullHeartbeat.player_name);
  assert.equal(session.deviceModel, fullHeartbeat.device_model);
  assert.equal(session.appVersion, fullHeartbeat.app_version);
}

test('HTTP presence accepts AI editor and transitions without counting another device', async (t) => {
  const server = await createServer(t);
  for (const state of ['launcher', 'ai_editor', 'game', 'ai_editor', 'launcher']) {
    const response = await server.inject({
      method: 'POST',
      url: '/api/presence/heartbeat',
      payload: { ...fullHeartbeat, state }
    });
    assert.equal(response.statusCode, 200);
    await assertPanelState(server, state);
  }
});

test('WebSocket full and minimal heartbeats report AI editor and restore normal states', { timeout: 10000 }, async (t) => {
  const server = await createServer(t);
  await server.listen({ host: '127.0.0.1', port: 0 });
  const socket = new WebSocket(`ws://127.0.0.1:${server.server.address().port}/api/presence/ws`);
  try {
    await once(socket, 'open');
    for (const [index, state] of ['ai_editor', 'launcher', 'ai_editor', 'game'].entries()) {
      const nextMessage = once(socket, 'message');
      socket.send(JSON.stringify(index === 0
        ? { ...fullHeartbeat, state }
        : { type: 'presence', client_id: fullHeartbeat.client_id, state, sent_at: Date.now() }));
      const [raw] = await nextMessage;
      const ack = JSON.parse(raw.toString());
      assert.equal(ack.type, 'presence_ack');
      assert.equal(ack.ok, true);
      await assertPanelState(server, state);
    }
  } finally {
    // Disconnect even after a failed assertion, before server.close() runs.
    socket.terminate();
  }
});
