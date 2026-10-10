/**
 * Local/dev orchestrator: boots all services in one process with in-memory
 * stores (production deployments run them as separate containers — see
 * docker-compose.yml and k8s/).
 */
import { EventEmitter } from 'node:events';
import { loadConfig } from './shared/config.js';
import { logger } from './shared/logger.js';
import { createMemoryStore } from './shared/stores.js';
import { buildAuthApp } from './services/auth/main.js';
import { attachSignaling } from './services/signaling/main.js';
import { buildPresenceApp } from './services/presence/main.js';
import { buildFileRelayApp } from './services/file-relay/main.js';
import { buildPushApp } from './services/push/main.js';
import { createServer } from 'node:http';

async function main() {
  const bus = new EventEmitter();
  const store = createMemoryStore();

  const auth = await buildAuthApp({ store, config: loadConfig({ port: 8081 }) });
  const presence = buildPresenceApp({ store, config: loadConfig({ port: 8083 }), bus });
  const fileRelay = await buildFileRelayApp({ config: loadConfig({ port: 8084 }) });
  const push = buildPushApp({ store, config: loadConfig({ port: 8085 }), bus });

  await auth.listen({ port: 8081, host: '0.0.0.0' });
  await presence.listen({ port: 8083, host: '0.0.0.0' });
  await fileRelay.listen({ port: 8084, host: '0.0.0.0' });
  await push.listen({ port: 8085, host: '0.0.0.0' });

  const signalingServer = createServer();
  attachSignaling(signalingServer, {
    store,
    config: loadConfig({ port: 8082 }),
    bus,
    // Wake = nudge FCM (here: the local push service directly via the bus).
    wake: (userId) => bus.emit('wake', { userId, reason: 'envelope_queued_or_delivered' }),
  });
  signalingServer.listen(8082, '0.0.0.0', () =>
    logger.info('Ara dev stack up: auth:8081 signaling:8082 presence:8083 file-relay:8084 push:8085'),
  );
}

main().catch((err) => {
  logger.error({ err }, 'dev stack failed to start');
  process.exit(1);
});
