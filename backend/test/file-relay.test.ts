import { test, describe } from 'node:test';
import assert from 'node:assert/strict';
import { createStack, createAccount, type TestStack } from './helpers.js';

describe('encrypted file relay (Phase 6 contract)', () => {
  const stacks: TestStack[] = [];
  const makeStack = async () => {
    const s = await createStack();
    stacks.push(s);
    return s;
  };

  test('chunked upload → download → delivery purge', async () => {
    const stack = await makeStack();
    const alice = await createAccount(stack.auth);
    const bob = await createAccount(stack.auth);
    const auth = { authorization: `Bearer ${alice.accessToken}` };
    const bobAuth = { authorization: `Bearer ${bob.accessToken}` };

    // The chunks are ENCRYPTED client-side; the relay never sees a key.
    const chunk1 = Buffer.from('ENC-CHUNK-0-⬛⬛⬛⬛⬛⬛⬛⬛');
    const chunk2 = Buffer.from('ENC-CHUNK-1-⬛⬛⬛⬛⬛⬛⬛⬛');

    const init = await stack.files.inject({
      method: 'POST',
      url: '/v1/files/init',
      headers: auth,
      payload: { sizeBytes: chunk1.length + chunk2.length, chunkCount: 2, chunkSize: 256 * 1024 },
    });
    assert.equal(init.statusCode, 201);
    const { fileId } = init.json();

    await stack.files.inject({ method: 'PUT', url: `/v1/files/${fileId}/chunks/0`, headers: { ...auth, 'content-type': 'application/octet-stream' }, payload: chunk1 });
    await stack.files.inject({ method: 'PUT', url: `/v1/files/${fileId}/chunks/1`, headers: { ...auth, 'content-type': 'application/octet-stream' }, payload: chunk2 });

    const complete = await stack.files.inject({ method: 'POST', url: `/v1/files/${fileId}/complete`, headers: auth });
    assert.equal(complete.statusCode, 200);

    // Recipient downloads chunks (in parallel in the real client).
    const got1 = await stack.files.inject({ method: 'GET', url: `/v1/files/${fileId}/chunks/0`, headers: bobAuth });
    assert.ok(got1.rawPayload.equals(chunk1), 'chunk must round-trip byte-for-byte');

    // Delivery confirmation purges the file from the relay.
    const purge = await stack.files.inject({ method: 'DELETE', url: `/v1/files/${fileId}`, headers: bobAuth });
    assert.equal(purge.statusCode, 200);
    const after = await stack.files.inject({ method: 'GET', url: `/v1/files/${fileId}/chunks/0`, headers: bobAuth });
    assert.equal(after.statusCode, 404);
  });

  test('rejects oversized chunks and oversized files', async () => {
    const stack = await makeStack();
    const alice = await createAccount(stack.auth);
    const auth = { authorization: `Bearer ${alice.accessToken}` };

    const tooBig = await stack.files.inject({
      method: 'POST',
      url: '/v1/files/init',
      headers: auth,
      payload: { sizeBytes: 10 * 1024 * 1024 * 1024, chunkCount: 1, chunkSize: 256 * 1024 },
    });
    assert.equal(tooBig.statusCode, 400);

    const init = await stack.files.inject({
      method: 'POST',
      url: '/v1/files/init',
      headers: auth,
      payload: { sizeBytes: 1024, chunkCount: 1, chunkSize: 256 * 1024 },
    });
    const { fileId } = init.json();
    const bigChunk = Buffer.alloc(5 * 1024 * 1024, 7);
    const res = await stack.files.inject({ method: 'PUT', url: `/v1/files/${fileId}/chunks/0`, headers: { ...auth, 'content-type': 'application/octet-stream' }, payload: bigChunk });
    assert.equal(res.statusCode, 400);
  });

  test('other users cannot read an in-flight upload before delivery', async () => {
    const stack = await makeStack();
    const alice = await createAccount(stack.auth);
    const mallory = await createAccount(stack.auth);
    const auth = { authorization: `Bearer ${alice.accessToken}` };

    const init = await stack.files.inject({
      method: 'POST',
      url: '/v1/files/init',
      headers: auth,
      payload: { sizeBytes: 10, chunkCount: 1, chunkSize: 256 * 1024 },
    });
    const { fileId } = init.json();
    await stack.files.inject({ method: 'PUT', url: `/v1/files/${fileId}/chunks/0`, headers: { ...auth, 'content-type': 'application/octet-stream' }, payload: Buffer.from('secretenc') });

    const peek = await stack.files.inject({
      method: 'GET',
      url: `/v1/files/${fileId}/chunks/0`,
      headers: { authorization: `Bearer ${mallory.accessToken}` },
    });
    assert.equal(peek.statusCode, 404);
  });

  test('complete fails until every chunk has arrived', async () => {
    const stack = await makeStack();
    const alice = await createAccount(stack.auth);
    const auth = { authorization: `Bearer ${alice.accessToken}` };
    const init = await stack.files.inject({
      method: 'POST',
      url: '/v1/files/init',
      headers: auth,
      payload: { sizeBytes: 100, chunkCount: 2, chunkSize: 256 * 1024 },
    });
    const { fileId } = init.json();
    await stack.files.inject({ method: 'PUT', url: `/v1/files/${fileId}/chunks/0`, headers: { ...auth, 'content-type': 'application/octet-stream' }, payload: Buffer.from('part1') });
    const complete = await stack.files.inject({ method: 'POST', url: `/v1/files/${fileId}/complete`, headers: auth });
    assert.equal(complete.statusCode, 400);
  });
});
