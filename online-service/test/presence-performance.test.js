'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const { openDatabase, initializeDatabase } = require('../src/server/db');
const { PresenceStore } = require('../src/server/presence');
const { buildServer } = require('../src/server/app');
const { loadConfig } = require('../src/server/config');

async function fixture(t) {
  const base = path.resolve(__dirname, '../../agent-tmp');
  fs.mkdirSync(base, { recursive: true });
  const dir = fs.mkdtempSync(path.join(base, 'presence-performance-'));
  const database = await openDatabase(path.join(dir, 'presence.sqlite'));
  t.after(async () => {
    await database.close();
    fs.rmSync(dir, { recursive: true, force: true });
  });
  return { dir, database, store: new PresenceStore(database, {}) };
}

test('device counter remains exact across inserts, upserts, deletes and restart', async (t) => {
  const { database, store } = await fixture(t);
  await store.recordHeartbeat({ client_id: 'one' });
  await store.recordHeartbeat({ client_id: 'one', state: 'launcher' });
  await store.recordHeartbeat({ client_id: 'two' });
  assert.equal((await store.buildSummary()).totalDevices, 2);
  await database.run('DELETE FROM presence_sessions WHERE client_id = ?', ['one']);
  await initializeDatabase(database);
  assert.equal((await store.buildSummary()).totalDevices, 1);
  // Simulate upgrading a populated database that predates the counter.
  await database.exec(`
    DROP TRIGGER presence_totals_insert;
    DROP TRIGGER presence_totals_delete;
    DROP TABLE presence_totals;
  `);
  await initializeDatabase(database);
  assert.equal((await store.buildSummary()).totalDevices, 1);
  await store.recordHeartbeat({ client_id: 'three' });
  assert.equal((await store.buildSummary()).totalDevices, 2);
});

test('heartbeat and session queries use bounded time index rather than historical scans', async (t) => {
  const { database, store } = await fixture(t);
  const now = Date.now();
  await database.run(`
    WITH RECURSIVE n(x) AS (VALUES(1) UNION ALL SELECT x+1 FROM n WHERE x < 36326)
    INSERT INTO presence_sessions(client_id, first_seen_at_ms, last_seen_at_ms)
    SELECT 'old-' || x, 1, 1 FROM n
  `);
  const queries = [];
  for (const method of ['get', 'all']) {
    const original = database[method].bind(database);
    database[method] = async (sql, params) => {
      queries.push({ sql, params: params || [] });
      return original(sql, params);
    };
  }
  const heartbeat = await store.recordHeartbeat({ client_id: 'live' }, now);
  assert.equal(heartbeat.online, 1);
  assert.equal(heartbeat.totalDevices, 36327);
  assert.equal(queries.length, 2);
  assert.match(queries[1].sql, /presence_totals/);
  await store.buildSnapshot(null, now);
  const coldCount = queries.length;
  await store.buildSnapshot(null, now + 1000);
  assert.equal(queries.length - coldCount, 3, 'warm snapshots only read live summary and sessions');
  const planned = queries.filter(({ sql }) => /WHERE last_seen_at_ms/.test(sql));
  for (const { sql, params } of planned) {
    const plan = await database.all('EXPLAIN QUERY PLAN ' + sql, params);
    const detail = plan.map((row) => row.detail).join('\n');
    assert.match(detail, /SEARCH presence_sessions USING (COVERING )?INDEX idx_presence_last_seen_state/);
    assert.doesNotMatch(detail, /SCAN presence_sessions/);
    if (/LIMIT/.test(sql)) assert.doesNotMatch(detail, /TEMP B-TREE FOR ORDER BY/);
  }
});

test('distribution cache merges readers, expires after a minute and rolls over at HK midnight', async (t) => {
  const { store } = await fixture(t);
  const beforeMidnight = Date.UTC(2026, 0, 1, 15, 59, 30);
  await store.recordHeartbeat({ client_id: 'one' }, beforeMidnight);
  let builds = 0;
  const original = store.buildDistributionScope.bind(store);
  store.buildDistributionScope = async (...args) => {
    builds += 1;
    return original(...args);
  };
  await Promise.all(Array.from({ length: 10 }, () => store.buildSnapshot(null, beforeMidnight)));
  assert.equal(builds, 2);
  await store.recordHeartbeat({ client_id: 'two' }, beforeMidnight + 1000);
  const cached = await store.buildSnapshot(null, beforeMidnight + 1000);
  assert.equal(cached.totalDevices, 2);
  assert.equal(cached.historicalDistribution.total, 1);
  assert.equal(builds, 2);
  const nextDay = await store.buildSnapshot(null, beforeMidnight + 30000);
  assert.equal(nextDay.todayDistribution.total, 0);
  assert.equal(builds, 3);
  const expired = await store.buildSnapshot(null, beforeMidnight + 60000);
  assert.equal(expired.historicalDistribution.total, 2);
  assert.equal(builds, 4);
});

test('failed distribution rebuilds are not cached', async (t) => {
  const { store } = await fixture(t);
  const original = store.buildDistributionScope.bind(store);
  store.buildDistributionScope = async () => { throw new Error('test failure'); };
  await assert.rejects(store.buildHistoricalDistribution(), /test failure/);
  store.buildDistributionScope = original;
  assert.equal((await store.buildHistoricalDistribution()).total, 0);
});

test('stats requests never issue writes and keep recorded hourly trends', async (t) => {
  const { database, store } = await fixture(t);
  const now = Date.now();
  await store.recordHourlySnapshot(now);
  await store.recordHeartbeat({ client_id: 'live' }, now);
  database.run = async () => { throw new Error('stats must not write'); };
  const stats = await store.buildStats(null, now);
  assert.equal(stats.currentOnline, 1);
  assert.equal(stats.totalDevices, 1);
  assert.equal(stats.buckets.at(-1).online, 0);
});

test('panel broadcasts are periodic, skip absent readers and never overlap', async (t) => {
  const { dir } = await fixture(t);
  t.mock.timers.enable({ apis: ['setInterval', 'setTimeout'] });
  let builds = 0;
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  t.mock.method(PresenceStore.prototype, 'buildSnapshot', async () => {
    builds += 1;
    if (builds >= 2) await gate;
    return { sessions: [] };
  });
  t.mock.method(PresenceStore.prototype, 'buildStats', async () => ({}));
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent' }),
    dbPath: path.join(dir, 'server.sqlite'),
    presencePanelToken: 'test-panel-token',
    panelSnapshotPushIntervalSeconds: 1
  });
  let socket;
  t.after(async () => {
    release();
    if (socket) socket.terminate();
    await server.close();
  });
  await server.ready();
  await server.inject({ method: 'POST', url: '/api/presence/heartbeat', payload: { client_id: 'one' } });
  t.mock.timers.tick(1000);
  assert.equal(builds, 0);
  socket = await server.injectWS('/api/presence/panel/ws?token=test-panel-token');
  assert.equal(builds, 1, 'connection receives an initial snapshot');
  for (let i = 0; i < 5; i += 1) {
    await server.inject({ method: 'POST', url: '/api/presence/heartbeat', payload: { client_id: 'one' } });
  }
  t.mock.timers.tick(300);
  assert.equal(builds, 1, 'heartbeats do not schedule extra snapshots');
  t.mock.timers.tick(700);
  assert.equal(builds, 2);
  t.mock.timers.tick(10000);
  assert.equal(builds, 2, 'timer ticks reuse an in-flight broadcast');
  release();
  await new Promise(setImmediate);
  t.mock.timers.tick(1000);
  assert.equal(builds, 3, 'broadcast resumes after the slow build completes');
  await new Promise(setImmediate);
  socket.terminate();
  await new Promise(setImmediate);
  t.mock.timers.tick(1000);
  assert.equal(builds, 3, 'broadcast stops after the last reader leaves');
});

test('hourly sampling runs without panel readers and is not driven by stats requests', async (t) => {
  const { dir } = await fixture(t);
  t.mock.timers.enable({
    apis: ['Date', 'setInterval', 'setTimeout'],
    now: Date.UTC(2026, 0, 1, 10)
  });
  let samples = 0;
  t.mock.method(PresenceStore.prototype, 'recordHourlySnapshot', async () => { samples += 1; });
  const server = await buildServer({
    ...loadConfig({ LOG_LEVEL: 'silent' }),
    dbPath: path.join(dir, 'sampler.sqlite'),
    presencePanelToken: 'test-panel-token'
  });
  t.after(() => server.close());
  await server.ready();
  assert.equal(samples, 1, 'startup samples the current hour');
  const response = await server.inject('/api/presence/stats?token=test-panel-token');
  assert.equal(response.statusCode, 200);
  assert.equal(samples, 1, 'stats requests are read-only');
  t.mock.timers.tick(5 * 60 * 1000);
  assert.equal(samples, 2, 'periodic sampling does not require a panel connection');
});
