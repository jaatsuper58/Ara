import Fastify, { FastifyInstance } from 'fastify';
import rateLimit from '@fastify/rate-limit';
import { z } from 'zod';
import { loadConfig, Config } from '../../shared/config.js';
import { logger } from '../../shared/logger.js';
import { verifyAccessToken } from '../../shared/jwt.js';
import { uuid } from '../../shared/ids.js';

export interface FileRelayDeps {
  config: Config;
}

interface FileRecord {
  ownerId: string;
  sizeBytes: number;
  chunkCount: number;
  chunkSize: number;
  chunks: Map<number, Buffer>;
  createdAt: number;
  complete: boolean;
}

/**
 * Encrypted file relay (spec §6, Phase 6).
 *
 * Stores ONLY opaque encrypted chunks (the per-file AES key is distributed
 * inside the E2EE message envelope, never through this API). Chunks are
 * purged on delivery confirmation (DELETE) or after 7 days undelivered.
 * In production this tier uses a tmpfs-backed cache and holds no durable state.
 */
export async function buildFileRelayApp(deps: FileRelayDeps): Promise<FastifyInstance> {
  const { config } = deps;
  const app = Fastify({ trustProxy: true, bodyLimit: 6 * 1024 * 1024 });
  const files = new Map<string, FileRecord>();

  // Encrypted chunks arrive as raw binary.
  app.addContentTypeParser(
    ['application/octet-stream', 'binary/octet-stream'],
    { parseAs: 'buffer' },
    (_request, body, done) => done(null, body as Buffer),
  );

  await app.register(rateLimit, { global: false, max: 600, timeWindow: '1 minute' });

  const authenticate = async (request: { headers: Record<string, string | string[] | undefined> }) => {
    const header = request.headers.authorization;
    if (typeof header !== 'string' || !header.startsWith('Bearer ')) return null;
    return verifyAccessToken(header.slice(7), config);
  };

  // TTL sweeper: purge undelivered files after 7 days.
  const sweeper = setInterval(() => {
    const cutoff = Date.now() - config.fileTtlDays * 86_400_000;
    for (const [id, file] of files) {
      if (file.createdAt < cutoff) {
        files.delete(id);
        logger.info({ event: 'file_expired', fileId: id }, 'expired file purged');
      }
    }
  }, 60 * 60_000);
  sweeper.unref();

  app.post('/v1/files/init', async (request, reply) => {
    const claims = await authenticate(request);
    if (!claims) return reply.code(401).send({ error: 'unauthorized' });
    const body = z
      .object({
        sizeBytes: z.number().int().min(1).max(config.maxFileSizeBytes),
        chunkCount: z.number().int().min(1).max(8192),
        chunkSize: z.number().int().min(1024).max(config.maxFileChunkBytes),
      })
      .parse(request.body);
    const fileId = uuid();
    files.set(fileId, {
      ownerId: claims.sub,
      sizeBytes: body.sizeBytes,
      chunkCount: body.chunkCount,
      chunkSize: body.chunkSize,
      chunks: new Map(),
      createdAt: Date.now(),
      complete: false,
    });
    logger.info({ event: 'file_init', fileId, chunks: body.chunkCount, ownerId: claims.sub }, 'upload initialized');
    return reply.code(201).send({ fileId, chunkSize: body.chunkSize });
  });

  app.put('/v1/files/:fileId/chunks/:index', async (request, reply) => {
    const claims = await authenticate(request);
    if (!claims) return reply.code(401).send({ error: 'unauthorized' });
    const { fileId } = request.params as { fileId: string };
    const index = parseInt((request.params as { index: string }).index, 10);
    const file = files.get(fileId);
    if (!file || file.ownerId !== claims.sub) return reply.code(404).send({ error: 'unknown_file' });
    if (index < 0 || index >= file.chunkCount) return reply.code(400).send({ error: 'bad_index' });

    const chunk = request.body as Buffer;
    if (!Buffer.isBuffer(chunk) || chunk.length === 0 || chunk.length > config.maxFileChunkBytes) {
      return reply.code(400).send({ error: 'bad_chunk' });
    }
    file.chunks.set(index, chunk);
    return reply.code(204).send();
  });

  app.get('/v1/files/:fileId/chunks/:index', async (request, reply) => {
    const claims = await authenticate(request);
    if (!claims) return reply.code(401).send({ error: 'unauthorized' });
    const { fileId } = request.params as { fileId: string };
    const index = parseInt((request.params as { index: string }).index, 10);
    const file = files.get(fileId);
    // Access model: chunks are hidden until the owner calls `complete` —
    // after that, any authenticated user holding the fileId (learned via an
    // E2EE envelope) may download; the chunks are ciphertext regardless.
    if (!file || !file.complete) return reply.code(404).send({ error: 'unknown_file' });
    const chunk = file.chunks.get(index);
    if (!chunk) return reply.code(404).send({ error: 'unknown_chunk' });
    return reply.code(200).send(chunk);
  });

  app.post('/v1/files/:fileId/complete', async (request, reply) => {
    const claims = await authenticate(request);
    if (!claims) return reply.code(401).send({ error: 'unauthorized' });
    const { fileId } = request.params as { fileId: string };
    const file = files.get(fileId);
    if (!file || file.ownerId !== claims.sub) return reply.code(404).send({ error: 'unknown_file' });
    if (file.chunks.size !== file.chunkCount) return reply.code(400).send({ error: 'incomplete', have: file.chunks.size, want: file.chunkCount });
    file.complete = true;
    return reply.send({ ok: true, fileId });
  });

  /** Delivery confirmation — recipient purges the file from the relay. */
  app.delete('/v1/files/:fileId', async (request, reply) => {
    const claims = await authenticate(request);
    if (!claims) return reply.code(401).send({ error: 'unauthorized' });
    const { fileId } = request.params as { fileId: string };
    const existed = files.delete(fileId);
    if (!existed) return reply.code(404).send({ error: 'unknown_file' });
    logger.info({ event: 'file_purged_on_delivery', fileId }, 'file purged after delivery ack');
    return reply.send({ ok: true, purged: true });
  });

  app.setErrorHandler((error, request, reply) => {
    if (error instanceof z.ZodError) return reply.code(400).send({ error: 'invalid_request' });
    if (typeof error.statusCode === 'number' && error.statusCode < 500) {
      return reply.code(error.statusCode).send({ error: 'request_failed' });
    }
    logger.error({ err: error, path: request.url }, 'file relay error');
    return reply.code(500).send({ error: 'internal' });
  });

  return app as FastifyInstance;
}

if (process.argv[1]?.endsWith('file-relay/main.js') || process.argv[1]?.endsWith('file-relay/main.ts')) {
  const app = await buildFileRelayApp({ config: loadConfig() });
  app.listen({ port: loadConfig().port, host: '0.0.0.0' }).then(() => logger.info('file relay listening'));
}
