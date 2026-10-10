import Fastify, { FastifyInstance } from 'fastify';
import { EventEmitter } from 'node:events';
import { loadConfig, Config } from '../../shared/config.js';
import { logger } from '../../shared/logger.js';
import { verifyAccessToken } from '../../shared/jwt.js';
import { UserStore } from '../../shared/stores.js';

export interface PushDeps {
  store: UserStore;
  config: Config;
  bus?: EventEmitter;
}

export interface PushAdapter {
  /** Sends a data-only wake-up. Implementations must never include content. */
  wake(userId: string, pushToken: string | null, reason: string): Promise<void>;
}

/**
 * FCM adapter stub. In production, wire `firebase-admin` here with service
 * credentials; the message contract is data-only {t:"wake"} — the visible
 * notification is generated on-device and contains no message content.
 */
export const fcmStubAdapter: PushAdapter = {
  async wake(userId, pushToken, reason) {
    logger.info(
      { event: 'push_wake', userId, reason, tokenSet: pushToken != null },
      'FCM data-only wake-up (stub; configure firebase-admin in production)',
    );
  },
};

/**
 * Push gateway: receives wake requests from the signaling tier (directly or
 * via the bus) and forwards them to FCM. Data-only, content-free.
 */
export function buildPushApp(deps: PushDeps, adapter: PushAdapter = fcmStubAdapter): FastifyInstance {
  const { store, config } = deps;
  const app = Fastify({ trustProxy: true });
  const bus = deps.bus ?? new EventEmitter();

  const authenticate = async (request: { headers: Record<string, string | string[] | undefined> }) => {
    const header = request.headers.authorization;
    if (typeof header !== 'string' || !header.startsWith('Bearer ')) return null;
    return verifyAccessToken(header.slice(7), config);
  };

  bus.on('wake', ({ userId, reason }: { userId: string; reason: string }) => {
    void (async () => {
      const devices = await store.getDevices(userId);
      for (const device of devices) {
        await adapter.wake(userId, device.pushToken, reason);
      }
    })();
  });

  app.post('/internal/push/wake', async (request, reply) => {
    const claims = await authenticate(request);
    if (!claims) return reply.code(401).send({ error: 'unauthorized' });
    const body = (request.body ?? {}) as { userId?: string; reason?: string };
    if (!body.userId) return reply.code(400).send({ error: 'userId required' });
    const devices = await store.getDevices(body.userId);
    for (const device of devices) {
      await adapter.wake(body.userId, device.pushToken, body.reason ?? 'envelope');
    }
    return reply.send({ ok: true, devices: devices.length });
  });

  return app as FastifyInstance;
}

if (process.argv[1]?.endsWith('push/main.js') || process.argv[1]?.endsWith('push/main.ts')) {
  const config = loadConfig();
  const app = buildPushApp({ store: (await import('../../shared/stores.js')).createMemoryStore(), config });
  app.listen({ port: config.port, host: '0.0.0.0' }).then(() => logger.info({ port: config.port }, 'push gateway listening'));
}
