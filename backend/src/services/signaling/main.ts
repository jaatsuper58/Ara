import { WebSocketServer, WebSocket } from 'ws';
import type { IncomingMessage } from 'node:http';
import type { Server } from 'node:http';
import { EventEmitter } from 'node:events';
import { z } from 'zod';
import { loadConfig, Config } from '../../shared/config.js';
import { logger } from '../../shared/logger.js';
import { verifyAccessToken } from '../../shared/jwt.js';
import { uuid } from '../../shared/ids.js';
import { UserStore } from '../../shared/stores.js';

/**
 * Signaling gateway (spec §6): one persistent WebSocket per device.
 *
 * PRIVACY GUARANTEE (§9): this service relays opaque encrypted envelopes and
 * call SDP/ICE. It never decrypts, never parses payload contents, and stores
 * nothing except a bounded, TTL'd offline queue of encrypted blobs. The logger
 * redacts every payload field.
 */

// Wire schemas — payload fields are opaque strings; zod checks shape only.
const envelopeSchema = z.object({
  id: z.string().max(128),
  sender: z.string().min(3).max(64),
  recipient: z.string().min(3).max(64),
  payload: z.string().max(256 * 1024), // opaque; size-capped
  type: z.string().max(32).default('1to1'),
  timestamp: z.number().optional(),
});

const messageSchema = z.discriminatedUnion('t', [
  z.object({ t: z.literal('envelope.send'), envelope: envelopeSchema }),
  z.object({ t: z.literal('ack.delivered'), to: z.string(), messageId: z.string().max(128) }),
  z.object({ t: z.literal('ack.read'), to: z.string(), chatId: z.string().max(256) }),
  z.object({ t: z.literal('call.offer'), callId: z.string(), to: z.string(), isVideo: z.boolean(), sdp: z.string().max(64 * 1024) }),
  z.object({ t: z.literal('call.answer'), callId: z.string(), to: z.string(), sdp: z.string().max(64 * 1024) }),
  z.object({ t: z.literal('call.ice'), callId: z.string(), to: z.string(), candidate: z.string().max(2048), sdpMid: z.string().nullable().optional(), sdpMLineIndex: z.number().int().optional() }),
  z.object({ t: z.literal('call.end'), callId: z.string(), to: z.string(), reason: z.string().max(64).optional() }),
  z.object({ t: z.literal('call.ringing'), callId: z.string(), to: z.string() }),
  z.object({ t: z.literal('call.decline'), callId: z.string(), to: z.string() }),
  z.object({ t: z.literal('call.join'), callId: z.string(), room: z.string().max(128) }),
  z.object({ t: z.literal('presence.typing'), to: z.string(), chatId: z.string().max(256), typing: z.boolean() }),
  z.object({ t: z.literal('presence.subscribe'), userIds: z.array(z.string().max(64)).max(2000) }),
]);

export interface SignalingDeps {
  store: UserStore;
  config: Config;
  /** Cross-process fan-out bus (Redis pub/sub in production; EventEmitter in dev/tests). */
  bus?: EventEmitter;
  /** Push adapter: invoked when the recipient has no live socket. */
  wake?: (userId: string) => void;
}

interface Connection {
  ws: WebSocket;
  userId: string;
  deviceId: string;
  subscriptions: Set<string>;
  alive: boolean;
}

export interface SignalingServerHandle {
  wss: WebSocketServer;
  connections: Map<string, Set<Connection>>;
  close(): void;
}

/** Token-bucket rate limit per connection. */
class RateLimiter {
  private tokens: number;
  private last = Date.now();
  constructor(private readonly capacity = 120, private readonly refillPerSec = 20) {
    this.tokens = capacity;
  }
  tryConsume(): boolean {
    const now = Date.now();
    this.tokens = Math.min(this.capacity, this.tokens + ((now - this.last) / 1000) * this.refillPerSec);
    this.last = now;
    if (this.tokens >= 1) {
      this.tokens -= 1;
      return true;
    }
    return false;
  }
}

export function attachSignaling(server: Server, deps: SignalingDeps): SignalingServerHandle {
  const { store, config } = deps;
  const bus = deps.bus ?? new EventEmitter();
  const wake = deps.wake ?? (() => {});

  const wss = new WebSocketServer({ server, path: '/v1/ws', maxPayload: 512 * 1024 });
  const connections = new Map<string, Set<Connection>>(); // userId -> conns

  const addConn = (conn: Connection) => {
    const set = connections.get(conn.userId) ?? new Set();
    set.add(conn);
    connections.set(conn.userId, set);
  };
  const removeConn = (conn: Connection) => {
    const set = connections.get(conn.userId);
    if (!set) return;
    set.delete(conn);
    if (set.size === 0) {
      connections.delete(conn.userId);
      broadcastOffline(conn.userId);
    }
  };

  const sendToUser = (userId: string, text: string): boolean => {
    const set = connections.get(userId);
    if (!set || set.size === 0) return false;
    let delivered = false;
    for (const conn of set) {
      if (conn.ws.readyState === WebSocket.OPEN) {
        conn.ws.send(text);
        delivered = true;
      }
    }
    return delivered;
  };

  const broadcastOffline = (userId: string) => {
    bus.emit('presence.offline', { userId });
    for (const [, set] of connections) {
      for (const conn of set) {
        if (conn.subscriptions.has(userId)) {
          conn.ws.send(JSON.stringify({ t: 'presence.update', userId, online: false, lastSeen: Date.now() }));
        }
      }
    }
  };

  wss.on('connection', async (ws: WebSocket, request: IncomingMessage) => {
    // Authenticate: JWT in ?token= (wss terminates TLS in prod).
    const url = new URL(request.url ?? '/', 'http://localhost');
    const claims = verifyAccessToken(url.searchParams.get('token') ?? '', config);
    if (!claims) {
      ws.close(4001, 'unauthorized');
      return;
    }
    const conn: Connection = {
      ws,
      userId: claims.sub,
      deviceId: claims.device,
      subscriptions: new Set(),
      alive: true,
    };
    addConn(conn);

    await store.updateUser(claims.sub, { lastSeen: Date.now() });

    // Deliver any envelopes queued while offline.
    const queued = await store.drainEnvelopes(claims.sub);
    for (const item of queued) {
      ws.send(item.blob); // opaque — queued verbatim
    }
    ws.send(JSON.stringify({ t: 'session.ready', userId: claims.sub }));

    // Announce online to subscribers (presence service consumes the bus in prod).
    bus.emit('presence.online', { userId: claims.sub, deviceId: claims.device });
    for (const [, set] of connections) {
      for (const other of set) {
        if (other.subscriptions.has(claims.sub) && other !== conn) {
          other.ws.send(JSON.stringify({ t: 'presence.update', userId: claims.sub, online: true }));
        }
      }
    }

    const limiter = new RateLimiter();

    ws.on('message', (data) => {
      if (!limiter.tryConsume()) {
        ws.send(JSON.stringify({ t: 'error', code: 'rate_limited', message: 'slow down' }));
        return;
      }
      let parsed: z.infer<typeof messageSchema>;
      try {
        parsed = messageSchema.parse(JSON.parse(String(data)));
      } catch {
        ws.send(JSON.stringify({ t: 'error', code: 'bad_message', message: 'unrecognized message' }));
        return;
      }

      switch (parsed.t) {
        case 'envelope.send': {
          const env = parsed.envelope;
          if (env.sender !== conn.userId) {
            ws.send(JSON.stringify({ t: 'error', code: 'sender_mismatch', message: 'sender must be authenticated user' }));
            return;
          }
          const wire = JSON.stringify({ t: 'envelope.receive', envelope: { ...env, timestamp: env.timestamp ?? Date.now() } });
          const delivered = sendToUser(env.recipient, wire);
          if (delivered) {
            // Push a silent wake-up to other devices of the recipient.
            wake(env.recipient);
          } else {
            void store.queueEnvelope({
              id: env.id,
              recipient: env.recipient,
              blob: wire,
              queuedAt: Date.now(),
            });
            wake(env.recipient);
          }
          logger.debug({ event: 'envelope_relayed', from: env.sender, to: env.recipient, type: env.type }, 'envelope relayed (content not logged)');
          break;
        }
        case 'ack.delivered':
        case 'ack.read': {
          const text = JSON.stringify({ ...parsed, from: conn.userId });
          sendToUser(parsed.to, text);
          break;
        }
        case 'call.offer':
        case 'call.answer':
        case 'call.ice':
        case 'call.end':
        case 'call.ringing':
        case 'call.decline': {
          // SDP/ICE relayed as opaque signaling payloads.
          const text = JSON.stringify({ ...parsed, from: conn.userId });
          sendToUser(parsed.to, text);
          break;
        }
        case 'call.join': {
          // SFU room membership: forwarded to the media tier via the bus.
          bus.emit('call.join', { callId: parsed.callId, room: parsed.room, userId: conn.userId });
          break;
        }
        case 'presence.typing': {
          const text = JSON.stringify({ t: 'presence.typing', chatId: parsed.chatId, userId: conn.userId, typing: parsed.typing });
          sendToUser(parsed.to, text);
          break;
        }
        case 'presence.subscribe': {
          for (const id of parsed.userIds) conn.subscriptions.add(id);
          // Immediately report current online state for subscribed users.
          const onlineNow = parsed.userIds.filter((id) => connections.has(id));
          if (onlineNow.length > 0) {
            ws.send(JSON.stringify({ t: 'presence.batch', online: onlineNow }));
          }
          break;
        }
      }
    });

    ws.on('pong', () => {
      conn.alive = true;
    });

    ws.on('close', () => removeConn(conn));
    ws.on('error', () => removeConn(conn));
  });

  // Heartbeat: drop dead connections.
  const heartbeat = setInterval(() => {
    for (const [, set] of connections) {
      for (const conn of set) {
        if (!conn.alive) {
          conn.ws.terminate();
          removeConn(conn);
          continue;
        }
        conn.alive = false;
        conn.ws.ping();
      }
    }
  }, 30_000);

  // Redis-backed multi-instance fan-out hooks (production).
  bus.on('fanout', ({ userId, text }: { userId: string; text: string }) => {
    sendToUser(userId, text);
  });

  return {
    wss,
    connections,
    close() {
      clearInterval(heartbeat);
      wss.close();
      for (const [, set] of connections) for (const c of set) c.ws.terminate();
    },
  };
}

/** Standalone entry point. */
if (process.argv[1]?.endsWith('signaling/main.js') || process.argv[1]?.endsWith('signaling/main.ts')) {
  const { createServer } = await import('node:http');
  const config = loadConfig();
  const server = createServer();
  attachSignaling(server, { store: (await import('../../shared/stores.js')).createMemoryStore(), config });
  server.listen(config.port, '0.0.0.0', () => logger.info({ port: config.port }, 'signaling gateway listening'));
}
