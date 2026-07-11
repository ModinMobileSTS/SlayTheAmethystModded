'use strict';

const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const {
  firstNonEmpty,
  parsePositiveInteger
} = require('./config');

const DEFAULT_EASYTIER_WEB_EMBED_API_SERVER_PORT = 11211;
const DEFAULT_EASYTIER_WEB_EMBED_API_SERVER_ADDR = '127.0.0.1';
const DEFAULT_EASYTIER_RUNTIME_DATA_DIR = './data/easytier-runtime';
const DEFAULT_EASYTIER_SHARED_NODE_INSTANCE_NAME = 'sts-online-shared-node';
const DEFAULT_EASYTIER_SHARED_NODE_HOSTNAME = 'sts-online-shared-node';
const DEFAULT_EASYTIER_SHARED_NODE_NETWORK_NAME = 'sts-online-shared-node';
const DEFAULT_EASYTIER_SHARED_NODE_RPC_PORTAL = '127.0.0.1:15888';
const DEFAULT_STOP_TIMEOUT_MS = 5000;
const DEFAULT_RESTART_DELAY_MS = 2000;

class EasyTierRuntimeManager {
  constructor(config, logger) {
    this.config = config || {};
    this.logger = normalizeLogger(logger);
    this.desiredState = 'stopped';
    this.components = {
      webEmbed: createComponentState('webEmbed'),
      sharedNode: createComponentState('sharedNode')
    };
    this.startPromise = null;
    this.stopPromise = null;
  }

  async start() {
    if (this.startPromise) {
      return this.startPromise;
    }
    this.startPromise = this.startInternal();
    try {
      return await this.startPromise;
    } finally {
      this.startPromise = null;
    }
  }

  async stop() {
    if (this.stopPromise) {
      return this.stopPromise;
    }
    this.stopPromise = this.stopInternal();
    try {
      return await this.stopPromise;
    } finally {
      this.stopPromise = null;
    }
  }

  async restart() {
    await this.stop();
    return this.start();
  }

  getStatus() {
    const webEmbed = buildDisplayComponentState('webEmbed', this.components.webEmbed, this.config);
    const sharedNode = buildDisplayComponentState('sharedNode', this.components.sharedNode, this.config);
    return {
      managed: Boolean(this.config.easyTierManaged),
      enabled: Boolean(this.config.easyTierEnabled),
      autoStart: Boolean(this.config.easyTierManagedAutoStart),
      desiredState: this.desiredState,
      runtimeDataDir: resolveRuntimeDataDir(this.config),
      restartOnExit: Boolean(this.config.easyTierManagedRestartOnExit),
      components: {
        webEmbed,
        sharedNode
      },
      summary: {
        runningCount: [webEmbed, sharedNode].filter((component) => component.running).length,
        configuredCount: [webEmbed, sharedNode].filter((component) => component.configured).length
      }
    };
  }

  getHealthSummary() {
    const status = this.getStatus();
    return {
      managed: status.managed,
      enabled: status.enabled,
      desiredState: status.desiredState,
      webEmbedRunning: Boolean(status.components.webEmbed.running),
      sharedNodeRunning: Boolean(status.components.sharedNode.running)
    };
  }

  async startInternal() {
    this.desiredState = 'running';
    if (!this.config.easyTierManaged || !this.config.easyTierEnabled) {
      return this.getStatus();
    }

    await this.startComponent('webEmbed');
    await this.startComponent('sharedNode');
    return this.getStatus();
  }

  async stopInternal() {
    this.desiredState = 'stopped';
    await Promise.all([
      this.stopComponent('sharedNode'),
      this.stopComponent('webEmbed')
    ]);
    return this.getStatus();
  }

  async startComponent(componentName) {
    const component = this.components[componentName];
    if (!component) {
      throw new Error(`Unknown EasyTier runtime component: ${componentName}`);
    }
    if (component.child) {
      return serializeComponentState(component);
    }
    clearPendingRestart(component);

    const spec = buildComponentSpec(componentName, this.config);
    component.configured = spec.configured;
    component.command = spec.command;
    component.args = spec.redactedArgs;
    component.cwd = spec.cwd;
    component.stdoutPath = spec.stdoutPath;
    component.stderrPath = spec.stderrPath;
    component.lastErrorMessage = spec.configured ? '' : spec.reason;

    if (!spec.configured) {
      return serializeComponentState(component);
    }

    ensureParentDirectory(spec.stdoutPath);
    ensureParentDirectory(spec.stderrPath);
    for (const dataPath of spec.dataPaths || []) {
      ensureParentDirectory(dataPath);
    }
    ensureParentDirectory(path.join(spec.cwd, '.keep'));

    const child = spawn(spec.command, spec.argsToSpawn, {
      cwd: spec.cwd,
      env: {
        ...process.env,
        ...spec.env
      },
      windowsHide: true,
      stdio: ['ignore', 'pipe', 'pipe']
    });

    component.child = child;
    component.running = true;
    component.stopRequested = false;
    component.pid = Number(child.pid) || 0;
    component.lastStartedAt = new Date().toISOString();
    component.lastExitedAt = null;
    component.lastExitCode = null;
    component.lastExitSignal = null;

    pipeChildOutput(child.stdout, spec.stdoutPath);
    pipeChildOutput(child.stderr, spec.stderrPath);

    child.once('error', (error) => {
      component.lastErrorMessage = String(error && error.message || error || 'Failed to start process');
      this.logger.warn({ error, component: componentName }, 'EasyTier child process error');
    });

    child.once('close', (code, signal) => {
      component.running = false;
      component.pid = 0;
      component.child = null;
      component.lastExitedAt = new Date().toISOString();
      component.lastExitCode = Number.isInteger(code) ? code : null;
      component.lastExitSignal = signal || null;
      if (!component.stopRequested && (code !== 0 || signal)) {
        component.lastErrorMessage = firstNonEmpty(
          component.lastErrorMessage,
          `Process exited unexpectedly (${formatExit(code, signal)})`
        );
      }
      const shouldRestart = this.desiredState === 'running' &&
        !component.stopRequested &&
        Boolean(this.config.easyTierManagedRestartOnExit);
      if (shouldRestart) {
        scheduleComponentRestart(this, componentName, component, this.config);
      }
    });

    return serializeComponentState(component);
  }

  async stopComponent(componentName) {
    const component = this.components[componentName];
    if (!component) {
      return null;
    }
    clearPendingRestart(component);
    component.stopRequested = true;
    const child = component.child;
    if (!child) {
      component.running = false;
      component.pid = 0;
      return serializeComponentState(component);
    }

    const exitPromise = new Promise((resolve) => {
      let settled = false;
      const timeout = setTimeout(() => {
        if (!settled) {
          try {
            child.kill('SIGKILL');
          } catch (_error) {
          }
        }
      }, resolveStopTimeoutMs(this.config));

      child.once('close', () => {
        if (settled) {
          return;
        }
        settled = true;
        clearTimeout(timeout);
        resolve();
      });
    });

    try {
      child.kill();
    } catch (error) {
      component.lastErrorMessage = String(error && error.message || error || 'Failed to stop process');
    }
    await exitPromise;
    return serializeComponentState(component);
  }
}

function createComponentState(name) {
  return {
    name,
    configured: false,
    running: false,
    pid: 0,
    command: '',
    args: [],
    cwd: '',
    stdoutPath: '',
    stderrPath: '',
    lastStartedAt: null,
    lastExitedAt: null,
    lastExitCode: null,
    lastExitSignal: null,
    lastErrorMessage: '',
    stopRequested: false,
    restartTimer: null,
    child: null
  };
}

function serializeComponentState(component) {
  return {
    name: component.name,
    configured: component.configured,
    running: component.running,
    pid: component.pid,
    command: component.command,
    args: Array.isArray(component.args) ? component.args.slice() : [],
    cwd: component.cwd,
    stdoutPath: component.stdoutPath,
    stderrPath: component.stderrPath,
    lastStartedAt: component.lastStartedAt,
    lastExitedAt: component.lastExitedAt,
    lastExitCode: component.lastExitCode,
    lastExitSignal: component.lastExitSignal,
    lastErrorMessage: component.lastErrorMessage
  };
}

function buildDisplayComponentState(componentName, component, config) {
  const spec = buildComponentSpec(componentName, config);
  return {
    name: component.name,
    configured: spec.configured,
    running: component.running,
    pid: component.pid,
    command: spec.command,
    args: Array.isArray(spec.redactedArgs) ? spec.redactedArgs.slice() : [],
    cwd: spec.cwd,
    stdoutPath: spec.stdoutPath,
    stderrPath: spec.stderrPath,
    lastStartedAt: component.lastStartedAt,
    lastExitedAt: component.lastExitedAt,
    lastExitCode: component.lastExitCode,
    lastExitSignal: component.lastExitSignal,
    lastErrorMessage: firstNonEmpty(
      component.lastErrorMessage,
      spec.configured ? '' : spec.reason
    )
  };
}

function buildComponentSpec(componentName, config) {
  if (componentName === 'webEmbed') {
    return buildWebEmbedSpec(config);
  }
  if (componentName === 'sharedNode') {
    return buildSharedNodeSpec(config);
  }
  throw new Error(`Unknown EasyTier runtime component: ${componentName}`);
}

function buildWebEmbedSpec(config) {
  const binaryPath = firstNonEmpty(config.easyTierWebEmbedBinaryPath);
  const runtimeDataDir = resolveRuntimeDataDir(config);
  const dbPath = path.join(runtimeDataDir, 'web-embed', 'et.db');
  const stdoutPath = path.join(runtimeDataDir, 'logs', 'easytier-web-embed.stdout.log');
  const stderrPath = path.join(runtimeDataDir, 'logs', 'easytier-web-embed.stderr.log');

  if (!binaryPath) {
    return buildSkippedComponentSpec(
      binaryPath,
      runtimeDataDir,
      stdoutPath,
      stderrPath,
      'EASYTIER_WEB_EMBED_BINARY_PATH is not configured'
    );
  }
  if (!isExistingBinaryPath(binaryPath)) {
    return buildSkippedComponentSpec(
      binaryPath,
      runtimeDataDir,
      stdoutPath,
      stderrPath,
      'EASYTIER_WEB_EMBED_BINARY_PATH does not exist'
    );
  }

  const args = [
    ...normalizeStringList(config.easyTierWebEmbedBinaryArgs),
    '--db', dbPath,
    '--config-server-port', String(config.easyTierConfigServerPort),
    '--config-server-protocol', String(config.easyTierConfigServerScheme),
    '--api-server-port', String(resolveEasyTierWebEmbedApiServerPort(config)),
    '--api-server-addr', String(firstNonEmpty(
      config.easyTierWebEmbedApiServerAddr,
      DEFAULT_EASYTIER_WEB_EMBED_API_SERVER_ADDR
    ))
  ];
  if (config.easyTierWebEmbedDisableWeb !== false) {
    args.push('--no-web');
  }
  const internalAuthToken = firstNonEmpty(config.easyTierWebEmbedInternalAuthToken);
  if (internalAuthToken) {
    args.push('--internal-auth-token', internalAuthToken);
  }
  return {
    configured: true,
    reason: '',
    command: path.resolve(binaryPath),
    argsToSpawn: args,
    redactedArgs: redactCommandArgs(args),
    cwd: runtimeDataDir,
    stdoutPath,
    stderrPath,
    dataPaths: [dbPath],
    env: {}
  };
}

function buildSharedNodeSpec(config) {
  const binaryPath = firstNonEmpty(config.easyTierCoreBinaryPath);
  const runtimeDataDir = resolveRuntimeDataDir(config);
  const stdoutPath = path.join(runtimeDataDir, 'logs', 'easytier-core.stdout.log');
  const stderrPath = path.join(runtimeDataDir, 'logs', 'easytier-core.stderr.log');
  const networkSecret = firstNonEmpty(config.easyTierSharedNodeNetworkSecret);

  if (!binaryPath) {
    return buildSkippedComponentSpec(
      binaryPath,
      runtimeDataDir,
      stdoutPath,
      stderrPath,
      'EASYTIER_CORE_BINARY_PATH is not configured'
    );
  }
  if (!isExistingBinaryPath(binaryPath)) {
    return buildSkippedComponentSpec(
      binaryPath,
      runtimeDataDir,
      stdoutPath,
      stderrPath,
      'EASYTIER_CORE_BINARY_PATH does not exist'
    );
  }
  if (!networkSecret) {
    return buildSkippedComponentSpec(
      binaryPath,
      runtimeDataDir,
      stdoutPath,
      stderrPath,
      'EASYTIER_SHARED_NODE_NETWORK_SECRET is not configured'
    );
  }

  const args = [
    ...normalizeStringList(config.easyTierCoreBinaryArgs),
    '--instance-name', firstNonEmpty(
      config.easyTierSharedNodeInstanceName,
      DEFAULT_EASYTIER_SHARED_NODE_INSTANCE_NAME
    ),
    '--hostname', firstNonEmpty(
      config.easyTierSharedNodeHostname,
      DEFAULT_EASYTIER_SHARED_NODE_HOSTNAME
    ),
    '--network-name', firstNonEmpty(
      config.easyTierSharedNodeNetworkName,
      DEFAULT_EASYTIER_SHARED_NODE_NETWORK_NAME
    ),
    '--network-secret', networkSecret,
    '--listeners', `tcp://0.0.0.0:${config.easyTierEntryNodePort}`,
    '--listeners', `udp://0.0.0.0:${config.easyTierEntryNodePort}`,
    '--rpc-portal', firstNonEmpty(
      config.easyTierSharedNodeRpcPortal,
      DEFAULT_EASYTIER_SHARED_NODE_RPC_PORTAL
    ),
    '--no-tun'
  ];
  const mappedListeners = buildMappedListeners(config);
  for (const mappedListener of mappedListeners) {
    args.push('--mapped-listeners', mappedListener);
  }
  const configServer = firstNonEmpty(config.easyTierSharedNodeConfigServer);
  if (configServer) {
    args.push('--config-server', configServer);
  }
  args.push(...normalizeStringList(config.easyTierCoreExtraArgs));

  return {
    configured: true,
    reason: '',
    command: path.resolve(binaryPath),
    argsToSpawn: args,
    redactedArgs: redactCommandArgs(args),
    cwd: runtimeDataDir,
    stdoutPath,
    stderrPath,
    dataPaths: [],
    env: {}
  };
}

function buildSkippedComponentSpec(command, cwd, stdoutPath, stderrPath, reason) {
  return {
    configured: false,
    reason,
    command: command ? path.resolve(command) : '',
    argsToSpawn: [],
    redactedArgs: [],
    cwd,
    stdoutPath,
    stderrPath,
    dataPaths: [],
    env: {}
  };
}

function scheduleComponentRestart(manager, componentName, component, config) {
  clearPendingRestart(component);
  component.restartTimer = setTimeout(() => {
    component.restartTimer = null;
    manager.startComponent(componentName).catch((error) => {
      component.lastErrorMessage = String(error && error.message || error || 'Failed to restart process');
      manager.logger.warn({ error, component: componentName }, 'Failed to restart EasyTier child process');
    });
  }, resolveRestartDelayMs(config));
}

function clearPendingRestart(component) {
  if (component && component.restartTimer) {
    clearTimeout(component.restartTimer);
    component.restartTimer = null;
  }
}

function pipeChildOutput(stream, filePath) {
  if (!stream || !filePath) {
    return;
  }
  const output = fs.createWriteStream(filePath, { flags: 'a' });
  stream.pipe(output);
}

function ensureParentDirectory(filePath) {
  fs.mkdirSync(path.dirname(path.resolve(filePath)), { recursive: true });
}

function isExistingBinaryPath(value) {
  try {
    return fs.existsSync(path.resolve(String(value || '').trim()));
  } catch (_error) {
    return false;
  }
}

function resolveRuntimeDataDir(config) {
  return path.resolve(firstNonEmpty(
    config && config.easyTierRuntimeDataDir,
    DEFAULT_EASYTIER_RUNTIME_DATA_DIR
  ));
}

function resolveEasyTierWebEmbedApiServerPort(config) {
  return parsePositiveInteger(
    config && config.easyTierWebEmbedApiServerPort,
    DEFAULT_EASYTIER_WEB_EMBED_API_SERVER_PORT
  );
}

function resolveStopTimeoutMs(config) {
  return parsePositiveInteger(
    config && config.easyTierManagedStopTimeoutMs,
    DEFAULT_STOP_TIMEOUT_MS
  );
}

function resolveRestartDelayMs(config) {
  return parsePositiveInteger(
    config && config.easyTierManagedRestartDelayMs,
    DEFAULT_RESTART_DELAY_MS
  );
}

function buildMappedListeners(config) {
  const listeners = [];
  const publicHost = extractAdvertiseHost(config);
  if (!publicHost) {
    return listeners;
  }
  listeners.push(`tcp://${publicHost}:${config.easyTierEntryNodePort}`);
  listeners.push(`udp://${publicHost}:${config.easyTierEntryNodePort}`);
  return listeners;
}

function extractAdvertiseHost(config) {
  const explicitEntryNodeHost = extractHostFromNetworkUrl(config && config.easyTierEntryNodeUrl);
  if (explicitEntryNodeHost) {
    return explicitEntryNodeHost;
  }
  const publicBaseHost = extractHostFromHttpUrl(config && config.publicBaseUrl);
  if (publicBaseHost) {
    return publicBaseHost;
  }
  return '';
}

function extractHostFromHttpUrl(value) {
  try {
    const parsed = new URL(String(value || '').trim());
    return formatHostForUrl(parsed.hostname || '');
  } catch (_error) {
    return '';
  }
}

function extractHostFromNetworkUrl(value) {
  const text = String(value || '').trim();
  if (!text) {
    return '';
  }
  const matched = text.match(/^[a-z][a-z0-9+.-]*:\/\/(\[[^\]]+\]|[^:/?#]+)(?::\d+)?/i);
  return matched ? formatHostForUrl(matched[1]) : '';
}

function formatHostForUrl(value) {
  const host = String(value || '').trim();
  if (!host) {
    return '';
  }
  if (host.includes(':') && !host.startsWith('[')) {
    return `[${host}]`;
  }
  return host;
}

function redactCommandArgs(args) {
  const source = Array.isArray(args) ? args : [];
  const redacted = [];
  for (let index = 0; index < source.length; index += 1) {
    const current = String(source[index] || '');
    redacted.push(current);
    if (current === '--network-secret' || current === '-s' ||
      current === '--internal-auth-token' || current === '--webhook-secret') {
      index += 1;
      if (index < source.length) {
        redacted.push('<redacted>');
      }
    }
  }
  return redacted;
}

function normalizeStringList(value) {
  if (Array.isArray(value)) {
    return value
      .map((item) => String(item || '').trim())
      .filter((item) => item.length > 0);
  }
  const text = String(value || '').trim();
  if (!text) {
    return [];
  }
  if (text.startsWith('[')) {
    try {
      const parsed = JSON.parse(text);
      return normalizeStringList(parsed);
    } catch (_error) {
    }
  }
  return text
    .split(/[;\r\n]+/)
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

function formatExit(code, signal) {
  if (Number.isInteger(code)) {
    return `code ${code}`;
  }
  if (signal) {
    return `signal ${signal}`;
  }
  return 'unknown exit';
}

function normalizeLogger(logger) {
  if (logger && typeof logger.info === 'function' && typeof logger.warn === 'function') {
    return logger;
  }
  return {
    info() {
    },
    warn() {
    },
    error() {
    },
    debug() {
    }
  };
}

module.exports = {
  EasyTierRuntimeManager,
  DEFAULT_EASYTIER_WEB_EMBED_API_SERVER_PORT,
  DEFAULT_EASYTIER_WEB_EMBED_API_SERVER_ADDR,
  DEFAULT_EASYTIER_RUNTIME_DATA_DIR,
  DEFAULT_EASYTIER_SHARED_NODE_INSTANCE_NAME,
  DEFAULT_EASYTIER_SHARED_NODE_HOSTNAME,
  DEFAULT_EASYTIER_SHARED_NODE_NETWORK_NAME,
  DEFAULT_EASYTIER_SHARED_NODE_RPC_PORTAL,
  buildComponentSpec,
  buildMappedListeners,
  redactCommandArgs,
  resolveRuntimeDataDir
};
