'use strict';

const { APP_NAME } = require('./src/constants');
const { loadConfig } = require('./src/config');
const { createApp } = require('./src/createApp');

const config = loadConfig();
const app = createApp(config);

if (require.main === module) {
  app.listen(config.port, () => {
    console.log(`${APP_NAME} listening on http://localhost:${config.port}`);
  });
}

module.exports = app;
