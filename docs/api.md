# Ara — API Surface (spec §6)

Base URLs (dev): auth `http://localhost:8081` · signaling `ws://localhost:8082/v1/ws` · presence `:8083` · file-relay `:8084` · push `:8085` (internal).
All authenticated calls: `Authorization: Bearer <accessToken>`.

## REST — Auth

| Method | Path | Body → Response |
| --- | --- | --- |
| POST | `/v1/account` | `{araId?, recoveryEmail?, publicKeyBundle, device}` → `201 {araId, accessToken, refreshToken, expiresInSeconds}` |
| POST | `/v1/account/otp` | `{phone}` → `{status:"sent"}` (5/min per IP; OTP only ever SMS-delivered, `debugCode` in `OTP_DEBUG=1` dev mode) |
| POST | `/v1/account/otp/verify` | `{phone, code, device?}` → session (10/min, 5 attempts per OTP) |
| POST | `/v1/session` | `{araId, device}` → `{accessToken, refreshToken, expiresInSeconds}` |
| POST | `/v1/session/refresh` | `{araId, refreshToken}` → rotated session (old refresh invalidated) |
| GET | `/v1/users/{araId}/keybundle` | → X3DH prekey bundle; **pops one one-time prekey** (single-use) |
| POST | `/v1/prekeys` | `{oneTimePreKeys:[{id, publicKey}]}` (≤500) — client refills as the pool drains |
| POST | `/v1/devices` | `{deviceId, platform, publicKey?, pushToken?}` |
| POST | `/v1/devices/push-token` | `{token, deviceId}` (FCM token, content-free wake-ups) |
| GET | `/v1/devices/me` | → this account's registered devices |

## REST — Groups

| Method | Path | Notes |
| --- | --- | --- |
| POST | `/v1/groups` | `{name, description?, memberLimit}` — creator becomes OWNER |
| PATCH | `/v1/groups/{id}` | `{name?, description?, photoUrl?}` (OWNER/ADMIN) |
| GET | `/v1/groups/{id}` | → group + members with roles |
| POST | `/v1/groups/{id}/members` | `{members:[araId]}` (invite) |
| DELETE | `/v1/groups/{id}/members/{userId}` | kick/leave (OWNER/ADMIN, or self) |

Group *content* (names excepted) stays client-side; membership metadata only.

## REST — File relay (encrypted chunks)

| Method | Path | Notes |
| --- | --- | --- |
| POST | `/v1/files/init` | `{sizeBytes, chunkCount, chunkSize}` → `{fileId}` (≤2 GiB, ≤4 MiB/chunk) |
| PUT | `/v1/files/{id}/chunks/{n}` | raw `application/octet-stream` ciphertext chunk (`nonce‖AES-GCM`) — owner-only, hidden until complete |
| POST | `/v1/files/{id}/complete` | validates all chunks present → file downloadable |
| GET | `/v1/files/{id}/chunks/{n}` | recipient download (chunks are ciphertext; the key arrived via E2EE envelope) |
| DELETE | `/v1/files/{id}` | **delivery confirmation** — purges immediately; TTL 7 days undelivered |

## REST — Presence

| Method | Path | Notes |
| --- | --- | --- |
| GET | `/v1/presence/{araId}` | `{online, lastSeen, typing}` — `lastSeen:null, hidden:true` when the user disabled status visibility |
| POST | `/v1/presence/{araId}/typing` | transient, never persisted |
| POST | `/v1/presence/visibility` | `{visible}` — per-user last-seen toggle (spec §2) |

## WebSocket — Signaling (one persistent connection per device)

Connect: `GET /v1/ws?token=<accessToken>` (wss in production). JSON messages, discriminator `t`.

**Client → server**
- `envelope.send {envelope{id, sender, recipient, payload(opaque), type, timestamp, v}}` — relayed verbatim; queued (≤100, 7-day TTL) when the recipient is offline; forged senders rejected
- `ack.delivered {to, messageId}` / `ack.read {to, chatId}`
- `call.offer {callId, to, isVideo, sdp}` · `call.answer` · `call.ice {candidate, sdpMid, sdpMLineIndex}` · `call.end` · `call.ringing` · `call.decline` · `call.join {room}` (SFU)
- `presence.typing {to, chatId, typing}` · `presence.subscribe {userIds}`

**Server → client**
- `session.ready {userId}` · `envelope.receive {envelope}` · `ack.delivered/read {…, from}` · `call.* {…, from}` · `presence.update {userId, online, lastSeen?}` · `presence.typing` · `error {code, message}`

Per-connection token bucket: 120 burst / 20 msg/s. Oversized payloads (>256 KiB) rejected.
