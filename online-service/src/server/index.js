'use strict';

const { buildServer } = require('./app');
const { loadConfig } = require('./config');

async function main() {
  const config = loadConfig();
  const server = await buildServer(config);
  await server.listen({
    host: config.host,
    port: config.port
  });
  if (config.easyTierManagedAutoStart) {
    try {
      await server.startEasyTierRuntime();
    } catch (error) {
      server.log.warn({ error }, 'Failed to auto start EasyTier runtime');
    }
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
