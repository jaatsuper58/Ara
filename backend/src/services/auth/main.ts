import Fastify, { FastifyInstance } from 'fastify';
import rateLimit from '@fastify/rate-limit';
import { z } from 'zod';
import { loadConfig, Config } from '../../shared/config.js';
import { logger } from '../../shared/logger.js';
import { createMemoryStore, UserStore, PublicKeyBundle, User, Device } from '../../shared/stores.js';
import { generateAraId, hashPhone, isValidAraId, randomOtpCode, randomToken, sha256Hex, uuid, hashCode, verifyCode } from '../../shared/ids.js';
import { signAccessToken } from '../../shared/jwt.js';

/** Pending OTP attempts (dev-grade in-memory; Redis-backed in production). */
interface PendingOtp {
  phoneHash: string;
  salt: Buffer;
  codeHash: string;
  expiresAt: number;
  attempts: number;
  userId: string | null; // set if the phone is already registered
}

export interface AuthDeps {
  store: UserStore;
  config: Config;
}

const bundleSchema = z.object({
  identityX25519: z.string().min(1),
  identityEd25519: z.string().min(1),
  signedPreKeyId: z.number().int(),
  signedPreKey: z.string().min(1),
  signedPreKeySignature: z.string().min(1),
});

const deviceSchema = z.object({
  deviceId: z.string().min(8).max(128),
  platform: z.string().max(32).default('android'),
  publicKey: z.string().max(512).optional(),
  pushToken: z.string().max(4096).optional(),
});

const createAccountSchema = z.object({
  araId: z.string().max(64).optional(),
  recoveryEmail: z.string().email().max(320).optional(),
  publicKeyBundle: bundleSchema,
  device: deviceSchema,
});

const sessionSchema = z.object({
  araId: z.string().min(4).max(64),
  device: deviceSchema,
});

const otpRequestSchema = z.object({ phone: z.string().min(6).max(20) });
const otpVerifySchema = z.object({
  phone: z.string().min(6).max(20),
  code: z.string().length(6),
  device: deviceSchema.optional(),
});

/** Builds the auth service (spec §6 REST — Auth). Exported for tests. */
export async function buildAuthApp(deps: AuthDeps): Promise<FastifyInstance> {
  const { store, config } = deps;
  const app: FastifyInstance = Fastify({ trustProxy: true });
  const pendingOtps = new Map<string, PendingOtp>();

  await app.register(rateLimit, { global: false });

  const issueSession = async (user: User, device: z.infer<typeof deviceSchema>) => {
    await store.upsertDevice({
      userId: user.id,
      id: device.deviceId,
      platform: device.platform,
      publicKey: device.publicKey ?? null,
      pushToken: device.pushToken ?? null,
    });
    const refreshToken = randomToken(32);
    await store.createSession({
      userId: user.id,
      deviceId: device.deviceId,
      refreshTokenHash: sha256Hex(refreshToken),
      expiresAt: Date.now() + config.refreshTokenTtlSeconds * 1000,
    });
    return {
      araId: user.id,
      accessToken: signAccessToken({ sub: user.id, device: device.deviceId }, config),
      refreshToken,
      expiresInSeconds: config.accessTokenTtlSeconds,
    };
  };

  const authenticate = async (request: { headers: Record<string, string | string[] | undefined> }) => {
    const header = request.headers.authorization;
    if (typeof header !== 'string' || !header.startsWith('Bearer ')) return null;
    const { verifyAccessToken } = await import('../../shared/jwt.js');
    return verifyAccessToken(header.slice(7), config);
  };

  // ---------- account ----------

  app.post('/v1/account', async (request, reply) => {
    const body = createAccountSchema.parse(request.body);

    // Honor a client-proposed Ara ID when free, else generate until unique.
    let araId = body.araId && isValidAraId(body.araId) ? body.araId : generateAraId();
    for (let i = 0; (await store.araIdTaken(araId)) && i < 5; i++) {
      if (body.araId) return reply.code(409).send({ error: 'araId_taken' });
      araId = generateAraId();
    }

    const user: User = {
      id: araId,
      createdAt: Date.now(),
      recoveryEmail: body.recoveryEmail ?? null,
      phoneHash: null,
      publicKeyBundle: body.publicKeyBundle as PublicKeyBundle,
      lastSeen: Date.now(),
      statusVisibility: true,
    };
    await store.createUser(user);
    logger.info({ araId, event: 'account_created' }, 'account created');
    const session = await issueSession(user, body.device);
    return reply.code(201).send(session);
  });

  // ---------- optional OTP phone path ----------

  app.post(
    '/v1/account/otp',
    { config: { rateLimit: { max: 5, timeWindow: '1 minute' } } },
    async (request, reply) => {
      const { phone } = otpRequestSchema.parse(request.body);
      const phoneHash = hashPhone(phone);
      const existing = await store.getUserByPhoneHash(phoneHash);
      const code = randomOtpCode();
      const salt = Buffer.from(uuid().replace(/-/g, ''), 'hex').subarray(0, 16);
      pendingOtps.set(phoneHash, {
        phoneHash,
        salt,
        codeHash: await hashCode(code, salt),
        expiresAt: Date.now() + 10 * 60 * 1000,
        attempts: 0,
        userId: existing?.id ?? null,
      });
      // Production: hand `code` to the SMS provider. Never logged.
      logger.info({ event: 'otp_requested', phoneHash: sha256Hex(phoneHash) }, 'otp requested');
      return reply.send({ status: 'sent', ...(config.otpDebug ? { debugCode: code } : {}) });
    },
  );

  app.post(
    '/v1/account/otp/verify',
    { config: { rateLimit: { max: 10, timeWindow: '1 minute' } } },
    async (request, reply) => {
      const body = otpVerifySchema.parse(request.body);
      const phoneHash = hashPhone(body.phone);
      const pending = pendingOtps.get(phoneHash);
      if (!pending || pending.expiresAt < Date.now()) {
        return reply.code(400).send({ error: 'otp_expired' });
      }
      pending.attempts += 1;
      if (pending.attempts > 5) {
        pendingOtps.delete(phoneHash);
        return reply.code(429).send({ error: 'too_many_attempts' });
      }
      if (!(await verifyCode(body.code, pending.salt, pending.codeHash))) {
        return reply.code(401).send({ error: 'otp_invalid' });
      }
      pendingOtps.delete(phoneHash);

      let user = pending.userId ? await store.getUser(pending.userId) : null;
      if (!user) {
        let araId = generateAraId();
        while (await store.araIdTaken(araId)) araId = generateAraId();
        // Placeholder bundle; replaced when the client uploads keys on first launch.
        user = {
          id: araId,
          createdAt: Date.now(),
          recoveryEmail: null,
          phoneHash,
          publicKeyBundle: { identityX25519: '', identityEd25519: '', signedPreKeyId: 0, signedPreKey: '', signedPreKeySignature: '' },
          lastSeen: Date.now(),
          statusVisibility: true,
        };
        await store.createUser(user);
      }
      const session = await issueSession(user, body.device ?? { deviceId: `otp-${uuid()}`, platform: 'android' });
      return reply.send(session);
    },
  );

  // ---------- sessions ----------

  app.post('/v1/session', async (request, reply) => {
    const body = sessionSchema.parse(request.body);
    const user = await store.getUser(body.araId);
    if (!user) return reply.code(404).send({ error: 'unknown_account' });
    const session = await issueSession(user, body.device);
    return reply.send(session);
  });

  app.post('/v1/session/refresh', async (request, reply) => {
    const body = z.object({ araId: z.string(), refreshToken: z.string().min(16) }).parse(request.body);
    const session = await store.findSessionByRefreshHash(sha256Hex(body.refreshToken));
    if (!session || session.userId !== body.araId) {
      return reply.code(401).send({ error: 'invalid_refresh' });
    }
    const user = await store.getUser(body.araId);
    if (!user) return reply.code(401).send({ error: 'invalid_refresh' });
    await store.deleteSessionsByRefreshHash(sha256Hex(body.refreshToken));
    const next = await issueSession(user, { deviceId: session.deviceId, platform: 'android' });
    return reply.send(next);
  });

  // ---------- key bundles (X3DH) ----------

  app.get('/v1/users/:araId/keybundle', async (request, reply) => {
    const claims = await authenticate(request);
    if (!claims) return reply.code(401).send({ error: 'unauthorized' });
    const { araId } = request.params as { araId: string };
    const user = await store.getUser(araId);
    if (!user || !user.publicKeyBundle.identityX25519) {
      return reply.code(404).send({ error: 'unknown_user' });
    }
    const opk = await store.popOneTimePreKey(araId);
    return reply.send({
      araId,
      identityX25519: user.publicKeyBundle.identityX25519,
      identityEd25519: user.publicKeyBundle.identityEd25519,
      signedPreKeyId: user.publicKeyBundle.signedPreKeyId,
      signedPreKey: user.publicKeyBundle.signedPreKey,
      signedPreKeySignature: user.publicKeyBundle.signedPreKeySignature,
      oneTimePreKeyId: opk?.keyId ?? null,
      oneTimePreKey: opk?.publicKey ?? null,
    });
  });

  app.post('/v1/prekeys', async (request, reply) => {
    const claims = await authenticate(request);
    if (!claims) return reply.code(401).send({ error: 'unauthorized' });
    const body = z
      .object({
        oneTimePreKeys: z
          .array(z.object({ id: z.number().int(), publicKey: z.string().min(1).max(256) }))
          .max(500),
      })
      .parse(request.body);
    const added = await store.addOneTimePreKeys(
      body.oneTimePreKeys.map((k) => ({ userId: claims.sub, keyId: k.id, publicKey: k.publicKey })),
    );
    return reply.send({ added });
  });

  // ---------- devices ----------

  app.post('/v1/devices', async (request, reply) => {
    const claims = await authenticate(request);
    if (!claims) return reply.code(401).send({ error: 'unauthorized' });
    const device = deviceSchema.parse(request.body);
    await store.upsertDevice({ userId: claims.sub, id: device.deviceId, platform: device.platform, publicKey: device.publicKey ?? null, pushToken: device.pushToken ?? null });
    return reply.send({ ok: true });
  });

  app.post('/v1/devices/push-token', async (request, reply) => {
    const claims = await authenticate(request);
    if (!claims) return reply.code(401).send({ error: 'unauthorized' });
    const body = z.object({ token: z.string().min(8), deviceId: z.string() }).parse(request.body);
    await store.setPushToken(claims.sub, body.deviceId, body.token);
    return reply.send({ ok: true });
  });

  app.get('/v1/devices/me', async (request, reply) => {
    const claims = await authenticate(request);
    if (!claims) return reply.code(401).send({ error: 'unauthorized' });
    const devices: Device[] = await store.getDevices(claims.sub);
    return reply.send(devices.map((d) => ({ deviceId: d.id, platform: d.platform, publicKey: d.publicKey, pushToken: d.pushToken ? '[set]' : null })));
  });

  // ---------- validation error mapping ----------

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof z.ZodError) {
      return reply.code(400).send({ error: 'invalid_request', details: error.issues.map((i) => i.path.join('.')) });
    }
    if (typeof error.statusCode === 'number' && error.statusCode < 500) {
      // Plugin-emitted statuses (e.g. 429 rate limiting) must pass through.
      return reply.code(error.statusCode).send({ error: error.statusCode === 429 ? 'rate_limited' : 'request_failed' });
    }
    logger.error({ err: error, path: request.url }, 'request failed');
    return reply.code(500).send({ error: 'internal' });
  });

  return app as FastifyInstance;
}

// Standalone entry point.
if (process.argv[1]?.endsWith('auth/main.js') || process.argv[1]?.endsWith('auth/main.ts')) {
  const config = loadConfig();
  const app = await buildAuthApp({ store: createMemoryStore(), config });
  app
    .listen({ port: config.port, host: '0.0.0.0' })
    .then(() => logger.info({ port: config.port }, 'auth service listening'))
    .catch((err) => {
      logger.error({ err }, 'listen failed');
      process.exit(1);
    });
}
