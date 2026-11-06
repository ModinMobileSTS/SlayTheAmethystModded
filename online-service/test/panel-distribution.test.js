'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');
const Vue = require('vue');

const clientSource = fs.readFileSync(path.join(__dirname, '../src/client/app.js'), 'utf8');

// Run the actual panel setup/watchers with Vue reactivity, without a DOM or canvas.
async function createPanel(t) {
  const scope = Vue.effectScope();
  const mounted = [];
  const unmounted = [];
  const mediaListeners = new Map();
  const charts = [];
  let panel;
  let dark = false;
  const mediaQuery = {
    get matches() { return dark; },
    addEventListener(type, listener) { mediaListeners.set(type, listener); },
    removeEventListener(type) { mediaListeners.delete(type); }
  };
  const context = {
    Vue: {
      ...Vue,
      createApp(options) {
        panel = scope.run(options.setup);
        return { use() { return this; }, mount() {} };
      },
      onMounted(callback) { mounted.push(callback); },
      onBeforeUnmount(callback) { unmounted.push(callback); }
    },
    Vuetify: { createVuetify() {} },
    window: {
      location: { search: '' },
      matchMedia() { return mediaQuery; },
      addEventListener() {},
      removeEventListener() {}
    },
    URL,
    URLSearchParams,
    echarts: {
      init() {
        const chart = {
          updates: [],
          actions: [],
          handlers: new Map(),
          setOption(option, settings) { this.updates.push({ option, settings }); },
          dispatchAction(action) { this.actions.push(action); },
          on(type, callback) { this.handlers.set(type, callback); },
          resize() {},
          dispose() { this.disposed = true; }
        };
        charts.push(chart);
        return chart;
      }
    }
  };
  vm.runInNewContext(clientSource, context, { filename: 'panel-app.js' });
  panel.distributionChartEl.value = {};
  mounted.forEach((callback) => callback());
  const flush = async () => {
    await Vue.nextTick();
    await Vue.nextTick();
  };
  await flush();
  t.after(() => {
    unmounted.forEach((callback) => callback());
    scope.stop();
  });
  return {
    panel,
    chart: charts[0],
    charts,
    flush,
    async push(snapshot) {
      panel.state.snapshot = structuredClone(snapshot);
      await flush();
    },
    async setTheme(value) {
      dark = value === 'dark';
      mediaListeners.get('change')();
      await flush();
    }
  };
}

function sampleSnapshot() {
  const distribution = {
    total: 4,
    deviceModels: [{ name: 'Xiaomi 14', value: 3 }, { name: 'OnePlus 13', value: 1 }],
    appVersions: [{ name: '1.6.5', value: 4 }],
    androidVersions: [{ name: '15', value: 4 }]
  };
  return {
    checkedAt: '2026-11-04T12:00:00Z',
    sessions: [
      { clientId: 'a', deviceModel: 'Xiaomi 14', appVersion: '1.6.5', androidVersion: '15' },
      { clientId: 'b', deviceModel: 'OnePlus 13', appVersion: '1.6.5', androidVersion: '15' }
    ],
    todayDistribution: structuredClone(distribution),
    historicalDistribution: structuredClone(distribution)
  };
}

test('heartbeat-only snapshots do not redraw the distribution rings', async (t) => {
  const { panel, chart, push } = await createPanel(t);
  const snapshot = sampleSnapshot();
  await push(snapshot);
  const updatesBeforeHeartbeats = chart.updates.length;
  for (let i = 1; i <= 3; i += 1) {
    snapshot.checkedAt = new Date(Date.parse(snapshot.checkedAt) + 2000).toISOString();
    snapshot.sessions.forEach((session) => {
      session.ageSeconds = i * 2;
      session.expiresInSeconds = 90 - i * 2;
      session.playerName = 'Updated name ' + i;
    });
    snapshot.sessions.reverse();
    await push(snapshot);
  }
  assert.equal(chart.updates.length, updatesBeforeHeartbeats);
  assert.equal(panel.snapshot.value.checkedAt, snapshot.checkedAt, 'other panel data stays live');
});

test('changed distributions merge into existing series instead of replaying entry animations', async (t) => {
  const { chart, charts, push } = await createPanel(t);
  const snapshot = sampleSnapshot();
  await push(snapshot);
  const previous = chart.updates.at(-1);
  snapshot.sessions.push({ clientId: 'c', deviceModel: 'Xiaomi 14', appVersion: '1.6.5', androidVersion: '15' });
  const before = chart.updates.length;
  await push(snapshot);
  assert.equal(charts.length, 1);
  assert.equal(chart.updates.length, before + 1);
  const { option, settings } = chart.updates.at(-1);
  assert.notEqual(settings, true, 'notMerge=true recreates the pie series');
  assert.notEqual(settings && settings.notMerge, true);
  assert.equal(option.animationDurationUpdate, 0);
  assert.equal(option.graphic[0].style.text, '3');
  assert.deepEqual(Array.from(option.series, (series) => series.id), Array.from(previous.option.series, (series) => series.id));
  assert.equal(option.series[0].data.find((item) => item.name === 'Xiaomi 14').value, 2);
});

test('source changes update once and inactive distributions do not trigger redraws', async (t) => {
  const { panel, chart, push, flush } = await createPanel(t);
  const snapshot = sampleSnapshot();
  await push(snapshot);
  for (const [source, label] of [['today', '当日'], ['history', '历史'], ['online', '在线']]) {
    const before = chart.updates.length;
    panel.selectedDistributionSource.value = source;
    await flush();
    assert.equal(chart.updates.length, before + 1, source + ' is rendered only once');
    assert.equal(chart.updates.at(-1).option.graphic[1].style.text, label);
    const afterSwitch = chart.updates.length;
    await push(snapshot);
    assert.equal(chart.updates.length, afterSwitch);
  }
  snapshot.historicalDistribution.total = 8;
  snapshot.todayDistribution.total = 8;
  const before = chart.updates.length;
  await push(snapshot);
  assert.equal(chart.updates.length, before);
});

test('empty distributions and returning categories remain refreshable', async (t) => {
  const { chart, push } = await createPanel(t);
  const snapshot = sampleSnapshot();
  await push(snapshot);
  snapshot.sessions = [];
  await push(snapshot);
  let option = chart.updates.at(-1).option;
  assert.equal(option.graphic[0].style.text, '0');
  assert.equal(option.graphic[1].style.text, '暂无数据');
  for (const series of option.series) {
    assert.equal(series.silent, true);
    assert.equal(series.data.length, 1);
    assert.equal(series.data[0].empty, true);
  }
  await push(sampleSnapshot());
  option = chart.updates.at(-1).option;
  assert.equal(option.graphic[0].style.text, '2');
  assert.equal(option.series[0].data.length, 2);
  assert.ok(option.series.every((series) => !series.silent && series.data.every((item) => !item.empty)));
});

test('theme changes repaint unchanged data without defeating heartbeat deduplication', async (t) => {
  const { chart, push, setTheme } = await createPanel(t);
  const snapshot = sampleSnapshot();
  await push(snapshot);
  const previous = chart.updates.at(-1).option;
  const before = chart.updates.length;
  await setTheme('dark');
  assert.equal(chart.updates.length, before + 1);
  assert.notEqual(chart.updates.at(-1).option.graphic[0].style.fill, previous.graphic[0].style.fill);
  await push(snapshot);
  assert.equal(chart.updates.length, before + 1);
});

test('updates do not force highlight or tooltip actions for an old hover target', async (t) => {
  const { chart, push } = await createPanel(t);
  const snapshot = sampleSnapshot();
  await push(snapshot);
  const option = chart.updates.at(-1).option;
  // A pointer can leave a slice for the hole/whitespace without leaving the canvas.
  const onMouseOver = chart.handlers.get('mouseover');
  if (onMouseOver) {
    onMouseOver({ componentType: 'series', seriesType: 'pie', seriesName: option.series[0].name, name: option.series[0].data[0].name, dataIndex: 0 });
  }
  snapshot.sessions = [snapshot.sessions[1]];
  await push(snapshot);
  assert.equal(chart.actions.length, 0, 'native ECharts hover handling must not be overridden on each snapshot');
});
