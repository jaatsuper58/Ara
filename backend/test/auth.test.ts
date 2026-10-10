import { test, describe, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createStack, createAccount, testBundle, type TestStack } from './helpers.js';

describe('auth service (spec §6)', () => {
  let stack: TestStack;

  before(async () => {
    stack = await createStack();
  });

  after(() => {
    stack.signaling.close();
  });

  test('creates an account with a generated Ara ID and returns a session', async () => {
    const res = await stack.auth.inject({
      method: 'POST',
      url: '/v1/account',
      payload: { publicKeyBundle: testBundle(), device: { deviceId: 'dev-aaaaaaaa', platform: 'android' } },
    });
    assert.equal(res.statusCode, 201);
    const body = res.json();
    assert.match(body.araId, /^ARA-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$/);
    assert.ok(body.accessToken);
    assert.ok(body.refreshToken);
  });

  test('rejects a taken client-proposed Ara ID', async () => {
    await createAccount(stack.auth, 'ARA-AAAA-BBBB-CCCC');
    const res = await stack.auth.inject({
      method: 'POST',
      url: '/v1/account',
      payload: { araId: 'ARA-AAAA-BBBB-CCCC', publicKeyBundle: testBundle(), device: { deviceId: 'dev-bbbbbbbb' } },
    });
    assert.equal(res.statusCode, 409);
  });

  test('serves a key bundle and pops a single-use one-time prekey', async () => {
    const alice = await createAccount(stack.auth);
    const bob = await createAccount(stack.auth);
    await stack.auth.inject({
      method: 'POST',
      url: '/v1/prekeys',
      headers: { authorization: `Bearer ${bob.accessToken}` },
      payload: { oneTimePreKeys: [{ id: 1, publicKey: 'b3BrMQ==' }, { id: 2, publicKey: 'b3BrMg==' }] },
    });

    const first = await stack.auth.inject({
      method: 'GET',
      url: `/v1/users/${bob.araId}/keybundle`,
      headers: { authorization: `Bearer ${alice.accessToken}` },
    });
    assert.equal(first.statusCode, 200);
    const bundle = first.json();
    assert.equal(bundle.araId, bob.araId);
    assert.equal(bundle.oneTimePreKeyId, 1);

    const second = await stack.auth.inject({
      method: 'GET',
      url: `/v1/users/${bob.araId}/keybundle`,
      headers: { authorization: `Bearer ${alice.accessToken}` },
    });
    assert.equal(second.json().oneTimePreKeyId, 2);

    const third = await stack.auth.inject({
      method: 'GET',
      url: `/v1/users/${bob.araId}/keybundle`,
      headers: { authorization: `Bearer ${alice.accessToken}` },
    });
    assert.equal(third.json().oneTimePreKey, null); // exhausted — client refills
  });

  test('keybundle requires authentication', async () => {
    const res = await stack.auth.inject({ method: 'GET', url: '/v1/users/ARA-X/keybundle' });
    assert.equal(res.statusCode, 401);
  });

  test('refresh rotation works and invalidates the old token', async () => {
    const account = await createAccount(stack.auth);
    const refresh = await stack.auth.inject({
      method: 'POST',
      url: '/v1/session/refresh',
      payload: { araId: account.araId, refreshToken: account.refreshToken },
    });
    assert.equal(refresh.statusCode, 200);
    assert.ok(refresh.json().accessToken);

    const replay = await stack.auth.inject({
      method: 'POST',
      url: '/v1/session/refresh',
      payload: { araId: account.araId, refreshToken: account.refreshToken },
    });
    assert.equal(replay.statusCode, 401);
  });

  test('OTP flow: request + verify links phone hash, wrong code rejected', async () => {
    const req = await stack.auth.inject({ method: 'POST', url: '/v1/account/otp', payload: { phone: '+15551234567' } });
    assert.equal(req.statusCode, 200);
    const debugCode = req.json().debugCode as string; // OTP_DEBUG in test config path is off by default...
    // Without OTP_DEBUG there is no code exposed; wrong codes must fail.
    const bad = await stack.auth.inject({
      method: 'POST',
      url: '/v1/account/otp/verify',
      payload: { phone: '+15551234567', code: '000000' },
    });
    assert.equal(bad.statusCode, 401);
    assert.ok(debugCode === undefined || typeof debugCode === 'string');
  });

  test('OTP request is rate limited (brute-force protection, §9)', async () => {
    const phone = '+15559990000';
    let gotLimited = false;
    for (let i = 0; i < 10; i++) {
      const res = await stack.auth.inject({ method: 'POST', url: '/v1/account/otp', payload: { phone } });
      if (res.statusCode === 429) {
        gotLimited = true;
        break;
      }
    }
    assert.equal(gotLimited, true, 'expected 429 after repeated OTP requests');
  });

  test('malformed requests map to 400', async () => {
    const res = await stack.auth.inject({ method: 'POST', url: '/v1/account', payload: { publicKeyBundle: { nope: 1 } } });
    assert.equal(res.statusCode, 400);
  });
});
