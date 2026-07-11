(function () {
  'use strict';

  const {
    createApp,
    computed,
    nextTick,
    onBeforeUnmount,
    onMounted,
    reactive,
    ref,
    watch
  } = Vue;
  const { createVuetify } = Vuetify;
  const HOUR_SECONDS = 60 * 60;
  const PRESENCE_SERVICE_BASE_URL = normalizeServiceBaseUrl(
    window.PRESENCE_SERVICE_BASE_URL,
    'https://heartbeat.nas.apricityx.top:23163'
  );

  const STATS_WINDOW_ITEMS = [
    { title: '24小时', value: 24 * HOUR_SECONDS },
    { title: '3天', value: 3 * 24 * HOUR_SECONDS },
    { title: '7天', value: 7 * 24 * HOUR_SECONDS },
    { title: '14天', value: 14 * 24 * HOUR_SECONDS },
    { title: '30天', value: 30 * 24 * HOUR_SECONDS }
  ];
  const DEFAULT_STATS_WINDOW_SECONDS = 7 * 24 * HOUR_SECONDS;
  const DISTRIBUTION_SOURCE_ITEMS = [
    { title: '在线数据', value: 'online' },
    { title: '当日数据', value: 'today' },
    { title: '历史数据', value: 'history' }
  ];
  const DEFAULT_DISTRIBUTION_SOURCE = 'online';
  const DISTRIBUTION_TOP_LIMIT = 5;
  const DEVICE_MODEL_COLORS = [
    '#2563eb',
    '#3b82f6',
    '#60a5fa',
    '#93c5fd',
    '#bfdbfe',
    '#dbeafe'
  ];
  const APP_VERSION_COLORS = [
    '#16a34a',
    '#22c55e',
    '#4ade80',
    '#bbf7d0'
  ];
  const ANDROID_VERSION_COLORS = [
    '#dc2626',
    '#f97316',
    '#f59e0b',
    '#facc15',
    '#fde68a'
  ];
  const DARK_SCHEME_QUERY = '(prefers-color-scheme: dark)';

  const METRIC_ITEMS = [
    {
      key: 'online',
      title: '当前在线',
      icon: 'mdi-account-multiple',
      color: 'primary',
      value(snapshot) {
        return String(Number(snapshot.online) || 0);
      },
      subtitle() {
        return '按最近心跳计算';
      }
    },
    {
      key: 'heartbeat',
      title: '心跳间隔',
      icon: 'mdi-heart-pulse',
      color: 'success',
      value(snapshot) {
        return (Number(snapshot.heartbeatIntervalSeconds) || 0) + 's';
      },
      subtitle() {
        return 'App WebSocket 上报';
      }
    },
    {
      key: 'totalOnline',
      title: '累计在线',
      icon: 'mdi-counter',
      color: 'warning',
      value(snapshot) {
        return String(getTotalOnlineUsers(snapshot));
      },
      subtitle() {
        return '历史唯一上报设备';
      }
    },
    {
      key: 'storage',
      title: '存储后端',
      icon: 'mdi-database',
      color: 'info',
      value(snapshot) {
        return snapshot.storageBackend || 'sqlite3';
      },
      subtitle(snapshot) {
        return snapshot.checkedAt ? formatDateTime(snapshot.checkedAt) : '等待快照';
      }
    }
  ];

  const SESSION_HEADERS = [
    { title: '设备', key: 'clientId', minWidth: 210 },
    { title: '玩家名', key: 'playerName', minWidth: 130 },
    { title: '机型', key: 'deviceModel', minWidth: 160 },
    { title: 'Android', key: 'androidVersion', minWidth: 130 },
    { title: 'ID 类型', key: 'idType', minWidth: 150 },
    { title: '状态', key: 'state', minWidth: 110 },
    { title: '版本', key: 'appVersion', minWidth: 100 },
    { title: '首次在线', key: 'firstSeenAt', minWidth: 170 },
    { title: '最近心跳', key: 'lastSeenAt', minWidth: 180 },
    { title: '剩余 TTL', key: 'expiresInSeconds', align: 'end', minWidth: 110 }
  ];
  const SESSION_SORT_OPTIONS = [
    { title: '玩家名', value: 'playerName', defaultOrder: 'asc' },
    { title: '最近心跳', value: 'lastSeenAt', defaultOrder: 'desc' },
    { title: '首次在线', value: 'firstSeenAt', defaultOrder: 'asc' },
    { title: '剩余 TTL', value: 'expiresInSeconds', defaultOrder: 'asc' },
    { title: '状态', value: 'state', defaultOrder: 'asc' },
    { title: '设备', value: 'clientId', defaultOrder: 'asc' },
    { title: '机型', value: 'deviceModel', defaultOrder: 'asc' },
    { title: 'Android', value: 'androidVersion', defaultOrder: 'asc' },
    { title: '版本', value: 'appVersion', defaultOrder: 'asc' }
  ];
  const DEFAULT_SESSION_SORT_KEY = 'playerName';
  const DEFAULT_SESSION_SORT_ORDER = 'asc';
  const SESSION_TEXT_COLLATOR = new Intl.Collator('zh-CN', {
    numeric: true,
    sensitivity: 'base'
  });

  function normalizeServiceBaseUrl(value, fallbackValue) {
    const rawValue = String(value || fallbackValue || '').trim();
    const normalized = rawValue.endsWith('/') ? rawValue.slice(0, -1) : rawValue;
    if (!normalized) {
      return '';
    }
    try {
      return new URL(normalized).origin;
    } catch (_error) {
      return new URL(fallbackValue).origin;
    }
  }

  function buildServiceUrl(pathname) {
    return new URL(pathname, PRESENCE_SERVICE_BASE_URL + '/');
  }

  function toWebSocketUrl(url) {
    return String(url || '').replace(/^https:/i, 'wss:').replace(/^http:/i, 'ws:');
  }

  function buildPanelWebSocketUrl(token, windowSeconds) {
    const url = buildServiceUrl('/api/presence/panel/ws');
    if (token) {
      url.searchParams.set('token', token);
    }
    url.searchParams.set('window_seconds', String(normalizeStatsWindowSeconds(windowSeconds)));
    return toWebSocketUrl(url.toString());
  }

  function readTokenFromLocation() {
    const params = new URLSearchParams(window.location.search);
    return (params.get('token') || params.get('key') || '').trim();
  }

  function formatDateTime(value) {
    if (!value) {
      return '-';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return '-';
    }
    return date.toLocaleString('zh-CN', {
      hour12: false,
      timeZone: 'Asia/Hong_Kong'
    });
  }

  function formatShortDateTime(value) {
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return '-';
    }
    const month = String(date.getMonth() + 1);
    const day = String(date.getDate());
    const hour = String(date.getHours()).padStart(2, '0');
    return month + '/' + day + ' ' + hour + ':00';
  }

  function formatAge(value) {
    const seconds = Number(value);
    if (!Number.isFinite(seconds)) {
      return '-';
    }
    return Math.max(0, seconds) + 's ago';
  }

  function normalizeStatsWindowSeconds(value) {
    const parsed = Number(value) || DEFAULT_STATS_WINDOW_SECONDS;
    const matched = STATS_WINDOW_ITEMS.find((item) => item.value === parsed);
    return matched ? matched.value : DEFAULT_STATS_WINDOW_SECONDS;
  }

  function formatStatsWindowLabel(value) {
    const normalized = normalizeStatsWindowSeconds(value);
    const matched = STATS_WINDOW_ITEMS.find((item) => item.value === normalized);
    return matched ? matched.title : '7天';
  }

  function maskIdentifier(value) {
    const normalized = String(value || '').trim();
    if (!normalized) {
      return 'unknown';
    }
    if (normalized.length <= 24) {
      return normalized;
    }
    return normalized.slice(0, 14) + '...' + normalized.slice(-8);
  }

  function getTotalOnlineUsers(snapshot) {
    const explicitValue = Number(snapshot && snapshot.totalOnlineUsers);
    if (Number.isFinite(explicitValue)) {
      return Math.max(0, explicitValue);
    }
    return Math.max(0, Number(snapshot && snapshot.totalDevices) || 0);
  }

  function getPreferredThemeName() {
    if (typeof window !== 'undefined' && window.matchMedia &&
      window.matchMedia(DARK_SCHEME_QUERY).matches) {
      return 'dark';
    }
    return 'light';
  }

  function normalizeRgbComponents(value) {
    const normalized = String(value || '').trim();
    if (!normalized) {
      return '';
    }
    if (normalized.includes(',')) {
      return normalized;
    }
    return normalized
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 3)
      .join(', ');
  }

  function readThemeRgb(name, fallback) {
    if (typeof window === 'undefined' || !window.getComputedStyle || typeof document === 'undefined') {
      return fallback;
    }
    const themeRoot = document.querySelector('.v-theme--dark, .v-theme--light') || document.documentElement;
    const value = window.getComputedStyle(themeRoot)
      .getPropertyValue('--v-theme-' + name);
    return normalizeRgbComponents(value) || fallback;
  }

  function rgb(components) {
    return 'rgb(' + components + ')';
  }

  function rgba(components, alpha) {
    return 'rgba(' + components + ', ' + alpha + ')';
  }

  function buildChartPalette() {
    const dark = getPreferredThemeName() === 'dark';
    const fallbackOnSurface = dark ? '248, 250, 252' : '17, 24, 39';
    const fallbackSurface = dark ? '23, 29, 36' : '255, 255, 255';
    const fallbackPrimary = dark ? '122, 167, 255' : '37, 99, 235';
    const onSurface = readThemeRgb('on-surface', fallbackOnSurface);
    const surface = readThemeRgb('surface', fallbackSurface);
    const primary = readThemeRgb('primary', fallbackPrimary);

    return {
      primary: rgb(primary),
      axisText: rgba(onSurface, .66),
      axisLine: rgba(onSurface, .22),
      centerText: rgb(onSurface),
      emptySlice: rgba(onSurface, .12),
      ringBorder: rgb(surface),
      secondaryText: rgba(onSurface, .68),
      splitLine: rgba(onSurface, .13),
      trendAreaEnd: rgba(primary, .03),
      trendAreaStart: rgba(primary, .24)
    };
  }

  function buildChartOption(stats) {
    const palette = buildChartPalette();
    const buckets = Array.isArray(stats && stats.buckets) ? stats.buckets : [];
    const samples = buckets
      .filter((bucket) => bucket && bucket.hasSnapshot !== false)
      .map((bucket) => ({
        label: formatShortDateTime(bucket.bucketStart),
        online: Math.max(0, Number(bucket.online) || 0),
        totalOnlineUsers: getTotalOnlineUsers(bucket),
        recordedAt: bucket.recordedAt ? formatDateTime(bucket.recordedAt) : '-'
      }));

    return {
      color: [palette.primary],
      animationDuration: 220,
      tooltip: {
        trigger: 'axis',
        confine: true,
        formatter(params) {
          const item = params && params[0] && params[0].data;
          if (!item) {
            return '';
          }
          return [
            '<strong>' + item.label + '</strong>',
            '该时刻在线: ' + item.online,
            '累计在线: ' + item.totalOnlineUsers,
            '记录时间: ' + item.recordedAt
          ].join('<br>');
        }
      },
      grid: {
        top: 28,
        right: 18,
        bottom: 42,
        left: 42,
        containLabel: true
      },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: samples.map((item) => item.label),
        axisLabel: {
          hideOverlap: true,
          color: palette.axisText
        },
        axisLine: {
          lineStyle: {
            color: palette.axisLine
          }
        },
        axisTick: {
          show: false
        }
      },
      yAxis: {
        type: 'value',
        name: '该时刻在线人数',
        nameTextStyle: {
          color: palette.axisText,
          fontSize: 12
        },
        minInterval: 1,
        axisLabel: {
          color: palette.axisText
        },
        splitLine: {
          lineStyle: {
            color: palette.splitLine
          }
        }
      },
      series: [
        {
          name: '该时刻在线人数',
          type: 'line',
          smooth: true,
          showSymbol: samples.length <= 36,
          symbolSize: 6,
          lineStyle: {
            width: 3
          },
          areaStyle: {
            color: {
              type: 'linear',
              x: 0,
              y: 0,
              x2: 0,
              y2: 1,
              colorStops: [
                { offset: 0, color: palette.trendAreaStart },
                { offset: 1, color: palette.trendAreaEnd }
              ]
            }
          },
          data: samples.map((item) => ({
            ...item,
            value: item.online
          }))
        }
      ]
    };
  }

  function buildDistributionChartOption(distribution, sourceMeta) {
    const palette = buildChartPalette();
    const normalizedDistribution = normalizeDistributionSummary(distribution);
    const meta = sourceMeta || getDistributionSourceMeta(DEFAULT_DISTRIBUTION_SOURCE);
    const deviceModels = normalizedDistribution.deviceModels;
    const appVersions = normalizedDistribution.appVersions;
    const androidVersions = normalizedDistribution.androidVersions;
    const total = Number(normalizedDistribution.total) || 0;
    const hasAnyData = deviceModels.length > 0 || appVersions.length > 0 || androidVersions.length > 0;

    return {
      backgroundColor: 'transparent',
      animationDuration: 220,
      animationDurationUpdate: 0,
      tooltip: {
        trigger: 'item',
        confine: true,
        formatter(params) {
          const percent = Number(params.percent);
          return [
            '<strong>' + params.seriesName + '</strong>',
            params.name + ': ' + params.value + ' (' + (Number.isFinite(percent) ? percent : 0) + '%)'
          ].join('<br>');
        }
      },
      graphic: [
        {
          type: 'text',
          left: 'center',
          top: '42%',
          style: {
            text: String(total),
            fill: palette.centerText,
            fontSize: 34,
            fontWeight: 800,
            textAlign: 'center'
          }
        },
        {
          type: 'text',
          left: 'center',
          top: '52%',
          style: {
            text: hasAnyData ? meta.centerLabel : '暂无数据',
            fill: palette.secondaryText,
            fontSize: 12,
            textAlign: 'center'
          }
        }
      ],
      series: [
        buildDistributionRingSeries('机型分布', ['24%', '36%'], deviceModels, DEVICE_MODEL_COLORS, palette),
        buildDistributionRingSeries('App 版本分布', ['43%', '55%'], appVersions, APP_VERSION_COLORS, palette),
        buildDistributionRingSeries('Android 版本分布', ['62%', '74%'], androidVersions, ANDROID_VERSION_COLORS, palette)
      ]
    };
  }

  function buildDistributionRingSeries(name, radius, data, colorSet, palette) {
    const normalizedData = data.length > 0 ? data : [{ name: '暂无数据', value: 1, empty: true }];
    return {
      name,
      id: name,
      type: 'pie',
      radius,
      center: ['50%', '48%'],
      avoidLabelOverlap: true,
      minAngle: 4,
      silent: data.length === 0,
      itemStyle: {
        borderColor: palette.ringBorder,
        borderWidth: 2
      },
      label: {
        show: false
      },
      emphasis: {
        scale: false,
        label: {
          show: data.length > 0,
          formatter: '{b}\n{d}%',
          color: palette.centerText,
          fontSize: 13,
          fontWeight: 650
        }
      },
      labelLine: {
        show: false
      },
      data: normalizedData.map((item, index) => ({
        ...item,
        itemStyle: {
          color: item.empty ? palette.emptySlice : colorSet[index % colorSet.length]
        }
      }))
    };
  }

  function buildSessionDistribution(sessions) {
    const normalizedSessions = Array.isArray(sessions) ? sessions : [];
    return {
      total: normalizedSessions.length,
      deviceModels: countTopValues(normalizedSessions, 'deviceModel'),
      appVersions: countTopValues(normalizedSessions, 'appVersion'),
      androidVersions: countTopValues(normalizedSessions, 'androidVersion')
    };
  }

  function buildHistoricalDistribution(snapshot) {
    return normalizeDistributionSummary(snapshot && snapshot.historicalDistribution);
  }

  function buildTodayDistribution(snapshot) {
    return normalizeDistributionSummary(snapshot && snapshot.todayDistribution);
  }

  function normalizeDistributionSummary(distribution) {
    return {
      total: Math.max(0, Number(distribution && distribution.total) || 0),
      deviceModels: normalizeDistributionItems(distribution && distribution.deviceModels),
      appVersions: normalizeDistributionItems(distribution && distribution.appVersions),
      androidVersions: normalizeDistributionItems(distribution && distribution.androidVersions)
    };
  }

  function normalizeDistributionItems(items) {
    return (Array.isArray(items) ? items : [])
      .map((item) => ({
        name: normalizeDistributionLabel(item && item.name),
        value: Math.max(0, Number(item && item.value) || 0)
      }))
      .filter((item) => item.value > 0);
  }

  function countTopValues(items, key) {
    const counts = new Map();
    for (const item of items) {
      const value = normalizeDistributionLabel(item && item[key]);
      counts.set(value, (counts.get(value) || 0) + 1);
    }

    const sorted = Array.from(counts.entries())
      .map(([name, value]) => ({ name, value }))
      .sort((a, b) => b.value - a.value || a.name.localeCompare(b.name));
    if (sorted.length <= DISTRIBUTION_TOP_LIMIT) {
      return sorted;
    }

    const topItems = sorted.slice(0, DISTRIBUTION_TOP_LIMIT);
    const otherValue = sorted
      .slice(DISTRIBUTION_TOP_LIMIT)
      .reduce((total, item) => total + item.value, 0);
    if (otherValue > 0) {
      topItems.push({
        name: 'Other',
        value: otherValue
      });
    }
    return topItems;
  }

  function normalizeDistributionLabel(value) {
    const normalized = String(value || '').trim();
    return normalized || 'unknown';
  }

  function normalizeDistributionSource(value) {
    return value === 'history' || value === 'today' ? value : DEFAULT_DISTRIBUTION_SOURCE;
  }

  function getDistributionSourceMeta(value) {
    const normalizedSource = normalizeDistributionSource(value);
    if (normalizedSource === 'history') {
      return {
        title: '历史分布',
        subtitle: '全部历史唯一上报设备 · 内圈机型，中圈 App 版本，外圈 Android 版本',
        centerLabel: '历史'
      };
    }
    if (normalizedSource === 'today') {
      return {
        title: '当日分布',
        subtitle: 'Asia/Hong_Kong 自然日内出现过的唯一设备 · 内圈机型，中圈 App 版本，外圈 Android 版本',
        centerLabel: '当日'
      };
    }
    return {
      title: '在线分布',
      subtitle: '当前在线会话 · 内圈机型，中圈 App 版本，外圈 Android 版本',
      centerLabel: '在线'
    };
  }

  function normalizeSessionSortOrder(value) {
    return value === 'desc' ? 'desc' : 'asc';
  }

  function getSessionSortOption(key) {
    const normalizedKey = String(key || '').trim();
    return SESSION_SORT_OPTIONS.find((item) => item.value === normalizedKey) || SESSION_SORT_OPTIONS[0];
  }

  function isBlankSortValue(value) {
    return value === null || value === undefined || String(value).trim().length === 0;
  }

  function compareBlankSortValues(a, b, order) {
    const aBlank = isBlankSortValue(a);
    const bBlank = isBlankSortValue(b);
    if (aBlank && bBlank) {
      return 0;
    }
    if (!aBlank && !bBlank) {
      return null;
    }
    if (order === 'desc') {
      return aBlank ? -1 : 1;
    }
    return aBlank ? 1 : -1;
  }

  function compareSessionTextValues(a, b, order) {
    const blankResult = compareBlankSortValues(a, b, order);
    if (blankResult !== null) {
      return blankResult;
    }
    return SESSION_TEXT_COLLATOR.compare(String(a), String(b));
  }

  function compareSessionNumberValues(a, b, order) {
    const blankResult = compareBlankSortValues(a, b, order);
    if (blankResult !== null) {
      return blankResult;
    }
    return Number(a) - Number(b);
  }

  function compareSessionDateValues(a, b, order) {
    const aTime = Date.parse(String(a || ''));
    const bTime = Date.parse(String(b || ''));
    const blankResult = compareBlankSortValues(
      Number.isFinite(aTime) ? aTime : '',
      Number.isFinite(bTime) ? bTime : '',
      order
    );
    if (blankResult !== null) {
      return blankResult;
    }
    return aTime - bTime;
  }

  createApp({
    setup() {
      const chartEl = ref(null);
      const distributionChartEl = ref(null);
      let chart = null;
      let distributionChart = null;
      let resizeObserver = null;
      let distributionResizeObserver = null;
      let themeMediaQuery = null;
      let distributionHoverState = null;
      let distributionChartEventsBound = false;
      const vuetifyTheme = Vuetify.useTheme ? Vuetify.useTheme() : null;

      const state = reactive({
        token: readTokenFromLocation(),
        inputToken: readTokenFromLocation(),
        ws: null,
        reconnectTimer: null,
        reconnectAttempt: 0,
        manuallyClosed: false,
        connectionStatus: 'idle',
        connectionMessage: '未连接',
        config: null,
        snapshot: null,
        stats: null,
        selectedDistributionSource: DEFAULT_DISTRIBUTION_SOURCE,
        selectedStatsWindowSeconds: DEFAULT_STATS_WINDOW_SECONDS,
        sessionSortBy: [
          {
            key: DEFAULT_SESSION_SORT_KEY,
            order: DEFAULT_SESSION_SORT_ORDER
          }
        ],
        lastError: ''
      });

      const hasToken = computed(() => state.token.trim().length > 0);
      const isConnected = computed(() => state.connectionStatus === 'connected');
      const isError = computed(() => state.connectionStatus === 'error');
      const isConnecting = computed(() => state.connectionStatus === 'connecting');
      const snapshot = computed(() => state.snapshot || {
        online: 0,
        byState: {},
        heartbeatIntervalSeconds: 0,
        offlineTimeoutSeconds: 0,
        checkedAt: '',
        storageBackend: 'sqlite3',
        totalDevices: 0,
        totalOnlineUsers: 0,
        historicalDistribution: {
          total: 0,
          deviceModels: [],
          appVersions: [],
          androidVersions: []
        },
        todayDistribution: {
          total: 0,
          deviceModels: [],
          appVersions: [],
          androidVersions: []
        },
        sessions: []
      });
      const stats = computed(() => state.stats || {
        peakOnline: 0,
        currentOnline: 0,
        totalOnlineUsers: 0,
        windowSeconds: state.selectedStatsWindowSeconds,
        snapshotCount: 0,
        buckets: [],
        since: '',
        until: ''
      });
      const sessions = computed(() => Array.isArray(snapshot.value.sessions)
        ? snapshot.value.sessions
        : []);
      const onlineDistribution = computed(() => buildSessionDistribution(sessions.value));
      const todayDistribution = computed(() => buildTodayDistribution(snapshot.value));
      const historicalDistribution = computed(() => buildHistoricalDistribution(snapshot.value));
      const selectedDistributionSource = computed({
        get() {
          return normalizeDistributionSource(state.selectedDistributionSource);
        },
        set(value) {
          state.selectedDistributionSource = normalizeDistributionSource(value);
        }
      });
      const selectedDistribution = computed(() => {
        if (selectedDistributionSource.value === 'history') {
          return historicalDistribution.value;
        }
        if (selectedDistributionSource.value === 'today') {
          return todayDistribution.value;
        }
        return onlineDistribution.value;
      });
      const selectedDistributionMeta = computed(() => getDistributionSourceMeta(selectedDistributionSource.value));
      const metricItems = computed(() => METRIC_ITEMS.map((item) => ({
        key: item.key,
        title: item.title,
        icon: item.icon,
        color: item.color,
        value: item.value(snapshot.value),
        subtitle: item.subtitle(snapshot.value)
      })));
      const hasStatsSamples = computed(() => (stats.value.buckets || [])
        .some((bucket) => bucket && bucket.hasSnapshot !== false));
      const selectedStatsWindowLabel = computed(() => formatStatsWindowLabel(state.selectedStatsWindowSeconds));
      const selectedSessionSort = computed(() => {
        const sort = Array.isArray(state.sessionSortBy) && state.sessionSortBy.length > 0
          ? state.sessionSortBy[0]
          : null;
        const option = getSessionSortOption(sort && sort.key);
        return {
          key: option.value,
          title: option.title,
          order: normalizeSessionSortOrder(sort && sort.order || option.defaultOrder)
        };
      });
      const selectedSessionSortKey = computed({
        get() {
          return selectedSessionSort.value.key;
        },
        set(value) {
          const option = getSessionSortOption(value);
          setSessionSort(option.value, option.defaultOrder);
        }
      });
      const selectedSessionSortOrder = computed(() => selectedSessionSort.value.order);
      const sessionSortLabel = computed(() => {
        return selectedSessionSort.value.title + (selectedSessionSort.value.order === 'desc' ? '降序' : '升序');
      });
      const sessionCustomSorters = {
        clientId: (a, b) => compareSessionTextValues(a, b, selectedSessionSortOrder.value),
        playerName: (a, b) => compareSessionTextValues(a, b, selectedSessionSortOrder.value),
        deviceModel: (a, b) => compareSessionTextValues(a, b, selectedSessionSortOrder.value),
        androidVersion: (a, b) => compareSessionTextValues(a, b, selectedSessionSortOrder.value),
        idType: (a, b) => compareSessionTextValues(a, b, selectedSessionSortOrder.value),
        state: (a, b) => compareSessionTextValues(a, b, selectedSessionSortOrder.value),
        appVersion: (a, b) => compareSessionTextValues(a, b, selectedSessionSortOrder.value),
        firstSeenAt: (a, b) => compareSessionDateValues(a, b, selectedSessionSortOrder.value),
        lastSeenAt: (a, b) => compareSessionDateValues(a, b, selectedSessionSortOrder.value),
        expiresInSeconds: (a, b) => compareSessionNumberValues(a, b, selectedSessionSortOrder.value)
      };
      const connectionColor = computed(() => {
        if (isConnected.value) {
          return 'success';
        }
        if (isError.value) {
          return 'error';
        }
        return 'warning';
      });
      const connectionIcon = computed(() => {
        if (isConnected.value) {
          return 'mdi-lan-connect';
        }
        if (isError.value) {
          return 'mdi-lan-disconnect';
        }
        return 'mdi-lan-pending';
      });

      function login() {
        const token = state.inputToken.trim();
        if (!token) {
          return;
        }
        const nextUrl = new URL(window.location.href);
        nextUrl.searchParams.set('token', token);
        window.history.replaceState(null, '', nextUrl.toString());
        state.token = token;
        connect();
      }

      function connect() {
        disconnect(false);
        if (!hasToken.value) {
          state.connectionStatus = 'idle';
          state.connectionMessage = '等待令牌';
          return;
        }

        state.manuallyClosed = false;
        state.connectionStatus = 'connecting';
        state.connectionMessage = '连接中';
        const ws = new WebSocket(buildPanelWebSocketUrl(state.token, state.selectedStatsWindowSeconds));
        state.ws = ws;

        ws.addEventListener('open', () => {
          state.reconnectAttempt = 0;
          state.connectionStatus = 'connected';
          state.connectionMessage = 'WS 已连接';
        });
        ws.addEventListener('message', (event) => {
          handleMessage(event.data);
        });
        ws.addEventListener('close', () => {
          if (state.ws === ws) {
            state.ws = null;
          }
          if (!state.manuallyClosed) {
            scheduleReconnect();
          }
        });
        ws.addEventListener('error', () => {
          state.connectionStatus = 'error';
          state.connectionMessage = '连接异常';
        });
      }

      function disconnect(markManual) {
        if (state.reconnectTimer) {
          window.clearTimeout(state.reconnectTimer);
          state.reconnectTimer = null;
        }
        state.manuallyClosed = Boolean(markManual);
        const ws = state.ws;
        state.ws = null;
        if (ws) {
          try {
            ws.close(1000, 'panel reconnect');
          } catch (_error) {
          }
        }
      }

      function scheduleReconnect() {
        state.connectionStatus = 'error';
        const delayMs = Math.min(30000, 1000 * Math.pow(2, Math.min(5, state.reconnectAttempt)));
        state.reconnectAttempt += 1;
        state.connectionMessage = '断开，' + Math.ceil(delayMs / 1000) + 's 后重连';
        state.reconnectTimer = window.setTimeout(connect, delayMs);
      }

      function handleMessage(rawText) {
        let message;
        try {
          message = JSON.parse(String(rawText || '{}'));
        } catch (_error) {
          return;
        }
        if (!message || typeof message !== 'object') {
          return;
        }
        if (message.type === 'config') {
          state.config = message;
          return;
        }
        if (message.type === 'snapshot') {
          state.snapshot = message.data || null;
          return;
        }
        if (message.type === 'stats') {
          state.stats = message.data || null;
          if (state.stats && state.stats.windowSeconds) {
            state.selectedStatsWindowSeconds = normalizeStatsWindowSeconds(state.stats.windowSeconds);
          }
          return;
        }
        if (message.type === 'error') {
          state.lastError = message.message || 'unknown';
          state.connectionStatus = 'error';
          state.connectionMessage = state.lastError;
        }
      }

      function send(type) {
        const ws = state.ws;
        if (!ws || ws.readyState !== WebSocket.OPEN) {
          connect();
          return;
        }
        ws.send(JSON.stringify({ type }));
      }

      function sendStatsRefresh() {
        const ws = state.ws;
        if (!ws || ws.readyState !== WebSocket.OPEN) {
          connect();
          return;
        }
        ws.send(JSON.stringify({
          type: 'refresh_stats',
          windowSeconds: state.selectedStatsWindowSeconds
        }));
      }

      function refreshAll() {
        send('refresh');
        sendStatsRefresh();
      }

      function selectStatsWindow(windowSeconds) {
        state.selectedStatsWindowSeconds = normalizeStatsWindowSeconds(windowSeconds);
        sendStatsRefresh();
      }

      function setSessionSort(key, order) {
        const option = getSessionSortOption(key);
        state.sessionSortBy = [
          {
            key: option.value,
            order: normalizeSessionSortOrder(order || option.defaultOrder)
          }
        ];
      }

      function toggleSessionSortOrder() {
        setSessionSort(
          selectedSessionSort.value.key,
          selectedSessionSort.value.order === 'desc' ? 'asc' : 'desc'
        );
      }

      function tableItem(item) {
        return item && item.raw ? item.raw : (item || {});
      }

      function ensureChart() {
        if (!hasStatsSamples.value || !chartEl.value || typeof echarts === 'undefined') {
          return;
        }
        if (!chart) {
          chart = echarts.init(chartEl.value, null, {
            renderer: 'canvas'
          });
        }
        chart.setOption(buildChartOption(stats.value), true);
      }

      function ensureDistributionChart() {
        if (!distributionChartEl.value || typeof echarts === 'undefined') {
          return;
        }
        if (!distributionChart) {
          distributionChart = echarts.init(distributionChartEl.value, null, {
            renderer: 'canvas'
          });
          bindDistributionChartEvents();
        }
        const option = buildDistributionChartOption(
          selectedDistribution.value,
          selectedDistributionMeta.value
        );
        const preservedHoverState = distributionHoverState;
        distributionChart.setOption(option, true);
        restoreDistributionChartHover(option, preservedHoverState);
      }

      function resizeChart() {
        if (chart) {
          chart.resize();
        }
        if (distributionChart) {
          distributionChart.resize();
        }
      }

      function syncThemeToSystem() {
        if (vuetifyTheme && vuetifyTheme.global && vuetifyTheme.global.name) {
          vuetifyTheme.global.name.value = getPreferredThemeName();
        }
        nextTick(() => {
          ensureChart();
          ensureDistributionChart();
        });
      }

      watch(stats, () => {
        nextTick(ensureChart);
      }, { deep: true });

      watch(() => state.selectedStatsWindowSeconds, () => {
        nextTick(ensureChart);
      });

      watch(() => state.sessionSortBy, (sortBy) => {
        if (!Array.isArray(sortBy) || sortBy.length === 0) {
          setSessionSort(DEFAULT_SESSION_SORT_KEY, DEFAULT_SESSION_SORT_ORDER);
        }
      }, { deep: true });

      watch(selectedDistribution, () => {
        nextTick(ensureDistributionChart);
      }, { deep: true });

      watch(selectedDistributionMeta, () => {
        nextTick(ensureDistributionChart);
      }, { deep: true });

      onMounted(() => {
        if (hasToken.value) {
          connect();
        }
        nextTick(ensureChart);
        nextTick(ensureDistributionChart);
        if (window.ResizeObserver && chartEl.value) {
          resizeObserver = new ResizeObserver(resizeChart);
          resizeObserver.observe(chartEl.value);
        }
        if (window.ResizeObserver && distributionChartEl.value) {
          distributionResizeObserver = new ResizeObserver(resizeChart);
          distributionResizeObserver.observe(distributionChartEl.value);
        }
        if (window.matchMedia) {
          themeMediaQuery = window.matchMedia(DARK_SCHEME_QUERY);
          if (themeMediaQuery.addEventListener) {
            themeMediaQuery.addEventListener('change', syncThemeToSystem);
          } else if (themeMediaQuery.addListener) {
            themeMediaQuery.addListener(syncThemeToSystem);
          }
        }
        window.addEventListener('resize', resizeChart);
      });

      onBeforeUnmount(() => {
        disconnect(true);
        window.removeEventListener('resize', resizeChart);
        if (themeMediaQuery) {
          if (themeMediaQuery.removeEventListener) {
            themeMediaQuery.removeEventListener('change', syncThemeToSystem);
          } else if (themeMediaQuery.removeListener) {
            themeMediaQuery.removeListener(syncThemeToSystem);
          }
          themeMediaQuery = null;
        }
        if (resizeObserver) {
          resizeObserver.disconnect();
          resizeObserver = null;
        }
        if (distributionResizeObserver) {
          distributionResizeObserver.disconnect();
          distributionResizeObserver = null;
        }
        if (chart) {
          chart.dispose();
          chart = null;
        }
        if (distributionChart) {
          distributionChart.dispose();
          distributionChart = null;
        }
        distributionHoverState = null;
        distributionChartEventsBound = false;
      });

      function bindDistributionChartEvents() {
        if (!distributionChart || distributionChartEventsBound) {
          return;
        }
        distributionChart.on('mouseover', (params) => {
          if (!params || params.componentType !== 'series' || params.seriesType !== 'pie') {
            return;
          }
          if (params.data && params.data.empty) {
            return;
          }
          distributionHoverState = {
            seriesName: String(params.seriesName || ''),
            itemName: String(params.name || ''),
            dataIndex: Math.max(0, Number(params.dataIndex) || 0)
          };
        });
        distributionChart.on('globalout', () => {
          distributionHoverState = null;
        });
        distributionChartEventsBound = true;
      }

      function restoreDistributionChartHover(option, hoverState) {
        if (!distributionChart || !hoverState) {
          return;
        }
        const target = findDistributionHoverTarget(option, hoverState);
        if (!target) {
          distributionHoverState = null;
          return;
        }
        distributionChart.dispatchAction({
          type: 'highlight',
          seriesIndex: target.seriesIndex,
          dataIndex: target.dataIndex
        });
        distributionChart.dispatchAction({
          type: 'showTip',
          seriesIndex: target.seriesIndex,
          dataIndex: target.dataIndex
        });
      }

      function findDistributionHoverTarget(option, hoverState) {
        const seriesList = Array.isArray(option && option.series) ? option.series : [];
        for (let seriesIndex = 0; seriesIndex < seriesList.length; seriesIndex += 1) {
          const series = seriesList[seriesIndex];
          if (String(series && series.name || '') !== hoverState.seriesName) {
            continue;
          }
          const dataList = Array.isArray(series && series.data) ? series.data : [];
          const matchedIndex = dataList.findIndex((item, index) => {
            if (item && item.empty) {
              return false;
            }
            if (String(item && item.name || '') === hoverState.itemName) {
              return true;
            }
            return index === hoverState.dataIndex;
          });
          if (matchedIndex >= 0) {
            return {
              seriesIndex,
              dataIndex: matchedIndex
            };
          }
        }
        return null;
      }

      return {
        chartEl,
        distributionChartEl,
        state,
        PRESENCE_SERVICE_BASE_URL,
        hasToken,
        isConnected,
        isConnecting,
        snapshot,
        stats,
        sessions,
        selectedDistributionSource,
        selectedDistributionMeta,
        metricItems,
        hasStatsSamples,
        selectedStatsWindowLabel,
        selectedSessionSortKey,
        selectedSessionSortOrder,
        sessionSortLabel,
        sessionCustomSorters,
        connectionColor,
        connectionIcon,
        STATS_WINDOW_ITEMS,
        DISTRIBUTION_SOURCE_ITEMS,
        SESSION_HEADERS,
        SESSION_SORT_OPTIONS,
        login,
        refreshAll,
        selectStatsWindow,
        toggleSessionSortOrder,
        tableItem,
        formatAge,
        formatDateTime,
        formatShortDateTime,
        maskIdentifier
      };
    },
    template: `
      <v-app>
        <v-main class="presence-shell">
          <v-container v-if="!hasToken" class="login-container" fluid>
            <v-card class="login-card" elevation="2">
              <v-card-title class="text-h5">在线情况面板</v-card-title>
              <v-card-text>
                <v-form @submit.prevent="login">
                  <v-text-field
                    v-model="state.inputToken"
                    label="访问令牌"
                    type="password"
                    autocomplete="current-password"
                    variant="outlined"
                    density="comfortable"
                    autofocus
                    hide-details
                  ></v-text-field>
                  <v-btn
                    class="mt-4"
                    color="primary"
                    type="submit"
                    block
                    size="large"
                    prepend-icon="mdi-login"
                  >
                    进入
                  </v-btn>
                </v-form>
              </v-card-text>
            </v-card>
          </v-container>

          <v-container v-else class="page-container" fluid>
            <v-toolbar class="top-toolbar" color="surface" rounded="lg" density="comfortable">
              <template #prepend>
                <v-icon icon="mdi-monitor-dashboard" size="28"></v-icon>
              </template>
              <v-toolbar-title>
                <div class="title-line">在线情况面板</div>
                <div class="subtitle-line">SlayTheAmethyst · {{ PRESENCE_SERVICE_BASE_URL }} · {{ snapshot.checkedAt || '-' }}</div>
              </v-toolbar-title>
              <v-spacer></v-spacer>
              <v-chip
                class="mr-2"
                :color="connectionColor"
                :prepend-icon="connectionIcon"
                variant="tonal"
              >
                {{ state.connectionMessage }}
              </v-chip>
              <v-btn
                color="primary"
                variant="flat"
                prepend-icon="mdi-refresh"
                :loading="isConnecting"
                @click="refreshAll"
              >
                刷新
              </v-btn>
            </v-toolbar>

            <v-row class="mt-4 overview-row" dense>
              <v-col cols="12" lg="7">
                <v-card class="distribution-card" elevation="1">
                  <v-card-title class="panel-title">
                    <div>
                      <div>{{ selectedDistributionMeta.title }}</div>
                      <div class="panel-subtitle">{{ selectedDistributionMeta.subtitle }}</div>
                    </div>
                    <v-spacer></v-spacer>
                    <v-btn-toggle
                      v-model="selectedDistributionSource"
                      class="distribution-source-toggle"
                      color="primary"
                      density="comfortable"
                      divided
                      mandatory
                      variant="outlined"
                    >
                      <v-btn
                        v-for="item in DISTRIBUTION_SOURCE_ITEMS"
                        :key="item.value"
                        :value="item.value"
                        size="small"
                      >
                        {{ item.title }}
                      </v-btn>
                    </v-btn-toggle>
                    <div class="distribution-ring-legend" aria-label="distribution ring legend">
                      <span><i class="legend-dot device"></i>机型</span>
                      <span><i class="legend-dot app"></i>App</span>
                      <span><i class="legend-dot android"></i>Android</span>
                    </div>
                  </v-card-title>
                  <v-card-text>
                    <div ref="distributionChartEl" class="distribution-chart"></div>
                  </v-card-text>
                </v-card>
              </v-col>

              <v-col cols="12" lg="5">
                <v-card class="metrics-overview-card" elevation="1">
                  <v-card-title class="panel-title">
                    <div>
                      <div>核心指标</div>
                      <div class="panel-subtitle">当前 WebSocket 快照</div>
                    </div>
                  </v-card-title>
                  <v-card-text class="metric-grid">
                    <v-card
                      v-for="item in metricItems"
                      :key="item.key"
                      class="metric-card"
                      :class="'metric-card-' + item.color"
                      elevation="0"
                    >
                      <v-card-text>
                        <v-icon class="metric-bg-icon" :icon="item.icon"></v-icon>
                        <div class="metric-heading">
                          <span>{{ item.title }}</span>
                        </div>
                        <div class="metric-value">{{ item.value }}</div>
                      </v-card-text>
                    </v-card>
                  </v-card-text>
                </v-card>
              </v-col>
            </v-row>

            <v-card class="mt-4" elevation="1">
              <v-card-title class="panel-title">
                <div>
                  <div>{{ selectedStatsWindowLabel }}在线趋势</div>
                  <div class="panel-subtitle">{{ formatShortDateTime(stats.since) }} - {{ formatShortDateTime(stats.until) }}</div>
                </div>
                <v-spacer></v-spacer>
                <v-btn-toggle
                  v-model="state.selectedStatsWindowSeconds"
                  class="stats-window-toggle"
                  color="primary"
                  density="comfortable"
                  divided
                  mandatory
                  variant="outlined"
                  @update:model-value="selectStatsWindow"
                >
                  <v-btn
                    v-for="item in STATS_WINDOW_ITEMS"
                    :key="item.value"
                    :value="item.value"
                    size="small"
                  >
                    {{ item.title }}
                  </v-btn>
                </v-btn-toggle>
                <v-chip color="primary" variant="tonal">峰值 {{ Number(stats.peakOnline) || 0 }}</v-chip>
                <v-chip color="info" variant="tonal">样本 {{ Number(stats.snapshotCount) || 0 }}/{{ (stats.buckets || []).length }}</v-chip>
              </v-card-title>
              <v-card-text>
                <div v-show="hasStatsSamples" ref="chartEl" class="presence-chart"></div>
                <div v-if="!hasStatsSamples" class="empty-state">
                  <v-icon icon="mdi-chart-line" size="44" color="primary"></v-icon>
                  <div class="empty-title">暂无趋势样本</div>
                  <div class="empty-text">打开面板后当前小时快照会自动写入，历史趋势按小时累积。</div>
                </div>
              </v-card-text>
            </v-card>

            <v-row class="mt-4" dense>
              <v-col cols="12">
                <v-card elevation="1">
                  <v-card-title class="panel-title">
                    <div>
                      <div>在线会话</div>
                      <div class="panel-subtitle">当前排序：{{ sessionSortLabel }}</div>
                    </div>
                    <v-spacer></v-spacer>
                    <div class="session-sort-controls">
                      <v-select
                        v-model="selectedSessionSortKey"
                        :items="SESSION_SORT_OPTIONS"
                        class="session-sort-select"
                        label="排序"
                        density="compact"
                        variant="outlined"
                        hide-details
                      ></v-select>
                      <v-btn
                        color="primary"
                        variant="tonal"
                        :prepend-icon="selectedSessionSortOrder === 'desc' ? 'mdi-sort-descending' : 'mdi-sort-ascending'"
                        @click="toggleSessionSortOrder"
                      >
                        {{ selectedSessionSortOrder === 'desc' ? '降序' : '升序' }}
                      </v-btn>
                    </div>
                  </v-card-title>
                  <v-data-table
                    :headers="SESSION_HEADERS"
                    :items="sessions"
                    v-model:sort-by="state.sessionSortBy"
                    :custom-key-sort="sessionCustomSorters"
                    density="comfortable"
                    fixed-header
                    height="520"
                    item-value="clientId"
                    must-sort
                    no-data-text="No active game sessions."
                    :items-per-page="25"
                  >
                    <template #item.clientId="{ item }">
                      <code class="identifier" :title="tableItem(item).clientId || ''">
                        {{ maskIdentifier(tableItem(item).clientId || tableItem(item).deviceId || 'unknown') }}
                      </code>
                    </template>
                    <template #item.playerName="{ item }">
                      {{ tableItem(item).playerName || '-' }}
                    </template>
                    <template #item.deviceModel="{ item }">
                      <span :title="tableItem(item).deviceModel || ''">
                        {{ tableItem(item).deviceModel || '-' }}
                      </span>
                    </template>
                    <template #item.androidVersion="{ item }">
                      {{ tableItem(item).androidVersion || '-' }}
                    </template>
                    <template #item.idType="{ item }">
                      {{ tableItem(item).idType || 'unknown' }}
                    </template>
                    <template #item.state="{ item }">
                      <v-chip color="primary" variant="tonal" size="small">{{ tableItem(item).state || 'unknown' }}</v-chip>
                    </template>
                    <template #item.appVersion="{ item }">
                      {{ tableItem(item).appVersion || '-' }}
                    </template>
                    <template #item.firstSeenAt="{ item }">
                      {{ formatDateTime(tableItem(item).firstSeenAt) }}
                    </template>
                    <template #item.lastSeenAt="{ item }">
                      <div>{{ formatDateTime(tableItem(item).lastSeenAt) }}</div>
                      <div class="table-subtitle">{{ formatAge(tableItem(item).ageSeconds) }}</div>
                    </template>
                    <template #item.expiresInSeconds="{ item }">
                      {{ Number(tableItem(item).expiresInSeconds) || 0 }}s
                    </template>
                  </v-data-table>
                </v-card>
              </v-col>
            </v-row>
          </v-container>
        </v-main>
      </v-app>
    `
  })
    .use(createVuetify({
      icons: {
        defaultSet: 'mdi'
      },
      theme: {
        defaultTheme: getPreferredThemeName(),
        themes: {
          light: {
            colors: {
              background: '#f6f8fb',
              surface: '#ffffff',
              primary: '#2563eb',
              success: '#0f9f6e',
              warning: '#b7791f',
              info: '#0284c7'
            }
          },
          dark: {
            colors: {
              background: '#101418',
              surface: '#171d24',
              primary: '#7aa7ff',
              success: '#61d394',
              warning: '#e5b95f',
              info: '#62c4f3'
            }
          }
        }
      },
      defaults: {
        VCard: {
          rounded: 'lg'
        },
        VBtn: {
          rounded: 'md'
        },
        VDataTable: {
          hover: true
        }
      }
    }))
    .mount('#app');
}());
