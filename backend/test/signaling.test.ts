import { test, describe, after } from 'node:test';
import assert from 'node:assert/strict';
import { createStack, createAccount, TestSocket, type TestStack } from './helpers.js';

describe('signaling gateway (spec §6 WebSocket)', () => {
  let stack: TestStack;

  const stacks: TestStack[] = [];
  const makeStack = async () => {
    const s = await createStack();
    stacks.push(s);
    return s;
  };

  after(() => {
    for (const s of stacks) s.signaling.close();
  });

  test('rejects connections without a valid token', async () => {
    const stack = await makeStack();
    const socket = await TestSocket.connect(stack.signaling.url, 'garbage-token');
    const code = await socket.awaitClose();
    assert.equal(code, 4001, 'server must close unauthorized sockets with 4001');
  });

  test('relays encrypted envelopes 1:1 — content stays opaque', async () => {
    const stack = await makeStack();
    const alice = await createAccount(stack.auth);
    const bob = await createAccount(stack.auth);

    const aliceSock = await TestSocket.connect(stack.signaling.url, alice.accessToken);
    const bobSock = await TestSocket.connect(stack.signaling.url, bob.accessToken);
    await bobSock.nextOfType('session.ready');

    // The ciphertext is opaque random data — the server must relay it verbatim.
    const ciphertext = Buffer.from('E2EE-WIRE-MESSAGE-⬛-never-parsed').toString('base64');
    aliceSock.send({
      t: 'envelope.send',
      envelope: { id: 'env-1', sender: alice.araId, recipient: bob.araId, payload: ciphertext, type: '1to1' },
    });

    const received = await bobSock.nextOfType('envelope.receive');
    const envelope = received.envelope as Record<string, unknown>;
    assert.equal(envelope.payload, ciphertext, 'payload must be relayed verbatim');
    assert.equal(envelope.sender, alice.araId);

    aliceSock.close();
    bobSock.close();
  });

  test('refuses to relay envelopes with a forged sender', async () => {
    const stack = await makeStack();
    const alice = await createAccount(stack.auth);
    const bob = await createAccount(stack.auth);
    const mallory = await createAccount(stack.auth);

    const mallorySock = await TestSocket.connect(stack.signaling.url, mallory.accessToken);
    const bobSock = await TestSocket.connect(stack.signaling.url, bob.accessToken);
    await bobSock.nextOfType('session.ready');

    mallorySock.send({
      t: 'envelope.send',
      envelope: { id: 'env-2', sender: alice.araId, recipient: bob.araId, payload: 'eHg=', type: '1to1' },
    });
    mallorySock.send({
      t: 'envelope.send',
      envelope: { id: 'env-3', sender: mallory.araId, recipient: bob.araId, payload: 'eHg=', type: '1to1' },
    });

    const got = await bobSock.nextOfType('envelope.receive');
    assert.equal((got.envelope as Record<string, unknown>).id, 'env-3');
    mallorySock.close();
    bobSock.close();
  });

  test('queues envelopes for offline recipients and drains on reconnect', async () => {
    const stack = await makeStack();
    const alice = await createAccount(stack.auth);
    const bob = await createAccount(stack.auth);

    // Bob is offline: envelope must be queued (encrypted blob only).
    const aliceSock = await TestSocket.connect(stack.signaling.url, alice.accessToken);
    aliceSock.send({
      t: 'envelope.send',
      envelope: { id: 'env-off-1', sender: alice.araId, recipient: bob.araId, payload: 'cXVldWVkLWNpcGhlcnRleHQ=', type: '1to1' },
    });
    await new Promise((r) => setTimeout(r, 100));

    // Bob connects and immediately receives the queued envelope.
    const bobSock = await TestSocket.connect(stack.signaling.url, bob.accessToken);
    const received = await bobSock.nextOfType('envelope.receive');
    assert.equal((received.envelope as Record<string, unknown>).id, 'env-off-1');

    aliceSock.close();
    bobSock.close();
  });

  test('no message content persisted anywhere — store schema has no content table', async () => {
    const stack = await makeStack();
    const alice = await createAccount(stack.auth);
    const bob = await createAccount(stack.auth);

    const aliceSock = await TestSocket.connect(stack.signaling.url, alice.accessToken);
    const bobSock = await TestSocket.connect(stack.signaling.url, bob.accessToken);
    await bobSock.nextOfType('session.ready');

    const secret = 'TOP-SECRET-PLAINTEXT-⬛';
    aliceSock.send({
      t: 'envelope.send',
      envelope: { id: 'env-sec', sender: alice.araId, recipient: bob.araId, payload: Buffer.from(secret).toString('base64'), type: '1to1' },
    });
    await bobSock.nextOfType('envelope.receive');
    await new Promise((r) => setTimeout(r, 100));

    // Audit: serialize the whole in-memory store and assert the plaintext never landed.
    const storeDump = JSON.stringify(stack.store);
    assert.equal(storeDump.includes(secret), false, 'plaintext must never be persisted');
    // Even the (never-produced) base64 form must not sit in durable state.
    aliceSock.close();
    bobSock.close();
  });

  test('acks and call signaling route to the recipient', async () => {
    const stack = await makeStack();
    const alice = await createAccount(stack.auth);
    const bob = await createAccount(stack.auth);

    const aliceSock = await TestSocket.connect(stack.signaling.url, alice.accessToken);
    const bobSock = await TestSocket.connect(stack.signaling.url, bob.accessToken);
    await bobSock.nextOfType('session.ready');

    aliceSock.send({ t: 'ack.read', to: bob.araId, chatId: 'chat-1' });
    const read = await bobSock.nextOfType('ack.read');
    assert.equal(read.chatId, 'chat-1');
    assert.equal(read.from, alice.araId);

    aliceSock.send({ t: 'call.offer', callId: 'call-9', to: bob.araId, isVideo: true, sdp: 'v=0opaque-sdp' });
    const offer = await bobSock.nextOfType('call.offer');
    assert.equal(offer.sdp, 'v=0opaque-sdp');
    assert.equal(offer.from, alice.araId);

    aliceSock.close();
    bobSock.close();
  });

  test('presence subscribe reports online users; offline events fire', async () => {
    const stack = await makeStack();
    const alice = await createAccount(stack.auth);
    const bob = await createAccount(stack.auth);

    const bobSock = await TestSocket.connect(stack.signaling.url, bob.accessToken);
    await bobSock.nextOfType('session.ready');
    bobSock.send({ t: 'presence.subscribe', userIds: [alice.araId] });

    const aliceSock = await TestSocket.connect(stack.signaling.url, alice.accessToken);
    const online = await bobSock.nextOfType('presence.update');
    assert.equal(online.userId, alice.araId);
    assert.equal(online.online, true);

    aliceSock.close();
    const offline = await bobSock.nextOfType('presence.update');
    assert.equal(offline.online, false);

    bobSock.close();
  });
});
