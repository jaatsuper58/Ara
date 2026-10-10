import { EventEmitter } from 'node:events';
import { buildAuthApp } from '../src/services/auth/main.js';
import { attachSignaling, SignalingServerHandle } from '../src/services/signaling/main.js';
import { buildFileRelayApp } from '../src/services/file-relay/main.js';
import { createMemoryStore, UserStore } from '../src/shared/stores.js';
import { loadConfig } from '../src/shared/config.js';
import { createServer } from 'node:http';
import { AddressInfo } from 'node:net';
import WebSocket from 'ws';

export interface TestStack {
  store: UserStore;
  auth: Awaited<ReturnType<typeof buildAuthApp>>;
  signaling: SignalingServerHandler;
  files: Awaited<ReturnType<typeof buildFileRelayApp>>;
}

export interface SignalingServerHandler {
  handle: SignalingServerHandle;
  url: string;
  close(): void;
}

export async function createStack(): Promise<TestStack> {
  const bus = new EventEmitter();
  const store = createMemoryStore();
  const config = loadConfig({ jwtSecret: 'test-secret' });
  const auth = await buildAuthApp({ store, config });
  const files = await buildFileRelayApp({ config });

  const server = createServer();
  const handle = attachSignaling(server, {
    store,
    config,
    bus,
    wake: () => {},
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', () => resolve()));
  const addr = server.address() as AddressInfo;

  return {
    store,
    auth,
    files,
    signaling: {
      handle,
      url: `ws://127.0.0.1:${addr.port}/v1/ws`,
      close: () => {
        handle.close();
        server.close();
      },
    },
  };
}

/** Creates an account and returns {araId, accessToken}. */
export async function createAccount(
  auth: TestStack['auth'],
  araId?: string,
): Promise<{ araId: string; accessToken: string; refreshToken: string }> {
  const res = await auth.inject({
    method: 'POST',
    url: '/v1/account',
    payload: {
      ...(araId ? { araId } : {}),
      publicKeyBundle: testBundle(),
      device: { deviceId: 'test-device-0001', platform: 'android' },
    },
  });
  if (res.statusCode !== 201) throw new Error(`account create failed: ${res.statusCode} ${res.body}`);
  return res.json();
}

export function testBundle() {
  return {
    identityX25519: Buffer.from(new Uint8Array(32).fill(1)).toString('base64'),
    identityEd25519: Buffer.from(new Uint8Array(32).fill(2)).toString('base64'),
    signedPreKeyId: 1,
    signedPreKey: Buffer.from(new Uint8Array(32).fill(3)).toString('base64'),
    signedPreKeySignature: Buffer.from(new Uint8Array(64).fill(4)).toString('base64'),
  };
}

/** JSON WebSocket helper with timeout. */
export class TestSocket {
  private ws: WebSocket;
  private queue: string[] = [];
  private waiters: ((value: string) => void)[] = [];

  constructor(url: string) {
    this.ws = new WebSocket(url);
    this.ws.on('message', (data) => {
      const text = String(data);
      const waiter = this.waiters.shift();
      if (waiter) waiter(text);
      else this.queue.push(text);
    });
  }

  static async connect(url: string, token: string): Promise<TestSocket> {
    const socket = new TestSocket(`${url}?token=${token}`);
    await new Promise<void>((resolve, reject) => {
      socket.ws.once('open', resolve);
      socket.ws.once('error', reject);
    });
    return socket;
  }

  /** Resolves with the close code when the server closes the socket. */
  awaitClose(timeoutMs = 3000): Promise<number> {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timeout waiting for close')), timeoutMs);
      this.ws.once('close', (code) => {
        clearTimeout(timer);
        resolve(code);
      });
    });
  }

  send(obj: unknown): void {
    this.ws.send(JSON.stringify(obj));
  }

  async next(timeoutMs = 3000): Promise<string> {
    const queued = this.queue.shift();
    if (queued) return queued;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timeout waiting for message')), timeoutMs);
      this.waiters.push((value) => {
        clearTimeout(timer);
        resolve(value);
      });
    });
  }

  async nextOfType(type: string, timeoutMs = 3000): Promise<Record<string, unknown>> {
    for (;;) {
      const raw = await this.next(timeoutMs);
      const msg = JSON.parse(raw);
      if (msg.t === type) return msg;
    }
  }

  close(): void {
    this.ws.close();
  }
}
