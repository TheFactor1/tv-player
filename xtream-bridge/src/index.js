#!/usr/bin/env node
import { loadConfig } from './config.js';
import { IdStore } from './ids.js';
import { Library } from './library.js';
import { createServer } from './server.js';
import { redact } from './addon.js';

let config;
try {
  config = loadConfig();
} catch (err) {
  console.error(err.message);
  process.exit(1);
}

const ids = new IdStore(config.dataDir);
const library = new Library(config, ids);
const server = createServer(config, library);

server.listen(config.port, config.host, () => {
  console.log(`Xtream bridge listening on ${config.host}:${config.port}`);
  console.log(`AIOStreams: ${redact(config.aioBase)}; log in as "${config.username}"`);
  // Warm the category list so the IPTV app's first load is quicker.
  Promise.all([library.categories('movie'), library.categories('series')]).then(
    ([m, s]) => console.log(`Found ${m.length} movie and ${s.length} series categories`),
    (err) => console.warn(`Could not load catalogs yet: ${err.message}`),
  );
});

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    ids.flush();
    server.close();
    process.exit(0);
  });
}
