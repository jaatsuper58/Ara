import Fastify, { FastifyInstance } from 'fastify';
import { loadConfig, Config } from '../../shared/config.js';
import { logger } from '../../shared/logger.js';
import { verifyAccessToken } from '../../shared/jwt.js';
import { UserStore } from '../../shared/stores.js';
import { EventEmitter } from 'node:events';

export interface PresenceDeps {
  store: UserStore;
  config: Config;
  bus?: EventEmitter;
}

/**
 * Presence service: online/last-seen/typing metadata.
 *
 * PRIVACY: last-seen is returned only when the queried user has enabled
 * status visibility (per-user toggle, spec §2). Typing state is transient and
 * never persisted.
 */
export function buildPresenceApp(deps: PresenceDeps): FastifyInstance {
  const { store, config } = deps;
  const app = Fastify({ trustProxy: true });
  const typing = new Map<string, number>(); // `${userId}:${chatId}` -> expiry

  const authenticate = async (request: { headers: Record<string, string | string[] | undefined> }) => {
    const header = request.headers.authorization;
    if (typeof header !== 'string' || !header.startsWith('Bearer ')) return null;
    return verifyAccessToken(header.slice(7), config);
  };

  app.get('/v1/presence/:araId', async (request, reply) => {
    const claims = await authenticate(request);
    if (!claims) return reply.code(401).send({ error: 'unauthorized' });
    const { araId } = request.params as { araId: string };
    const user = await store.getUser(araId);
    if (!user) return reply.code(404).send({ error: 'unknown_user' });

    if (!user.statusVisibility) {
      return reply.send({ araId, online: false, lastSeen: null, hidden: true });
    }
    const isTyping = [...typing.entries()]
      .some(([key, expiry]) => key.startsWith(`${araId}:`) && expiry > Date.now())
      ? true
      : false;
    return reply.send({
      araId,
      online: Date.now() - (user.lastSeen ?? 0) < 65_000,
      lastSeen: user.lastSeen,
      hidden: false,
      typing: isTyping,
    });
  });

  app.post('/v1/presence/:araId/typing', async (request, reply) => {
    const claims = await authenticate(request);
    if (!claims) return reply.code(401).send({ error: 'unauthorized' });
    const { araId } = request.params as { araId: string };
    const body = (request.body ?? {}) as { chatId?: string; typing?: boolean };
    const key = `${araId}:${body.chatId ?? ''}`;
    if (body.typing) typing.set(key, Date.now() + 5_000);
    else typing.delete(key);
    return reply.send({ ok: true });
  });

  app.post('/v1/presence/visibility', async (request, reply) => {
    const claims = await authenticate(request);
    if (!claims) return reply.code(401).send({ error: 'unauthorized' });
    const body = (request.body ?? {}) as { visible?: boolean };
    await store.updateUser(claims.sub, { statusVisibility: body.visible !== false });
    return reply.send({ ok: true });
  });

  return app as FastifyInstance;
}

if (process.argv[1]?.endsWith('presence/main.js') || process.argv[1]?.endsWith('presence/main.ts')) {
  const config = loadConfig();
  const app = buildPresenceApp({ store: (await import('../../shared/stores.js')).createMemoryStore(), config });
  app.listen({ port: config.port, host: '0.0.0.0' }).then(() => logger.info({ port: config.port }, 'presence service listening'));
}
