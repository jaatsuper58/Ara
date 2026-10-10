# Ara — Technical Architecture

## Client (Android)

| Concern | Choice |
| --- | --- |
| Language/UI | Kotlin + Jetpack Compose, Material 3 |
| Architecture | MVVM + Clean Architecture (presentation / domain / data), unidirectional state via `StateFlow` |
| DI | Hilt (KSP) |
| Storage | Room over **SQLCipher** (`net.zetetic:sqlcipher-android`), key wrapped by Android Keystore (`SecureBlobStore`) |
| REST | Retrofit + OkHttp (+ kotlinx-serialization converter) |
| Real-time | Persistent OkHttp WebSocket (`SignalingClient`) with exponential-backoff reconnect and an offline outbox |
| Calls | `org.webrtc` native SDK (prebuilt AAR via `io.getstream:stream-webrtc-android` — the same native lib Google published, not a wrapper SDK) |
| Background | WorkManager (`SyncWorker` retry/reconnect, `RetentionWorker` disappearing/retention purge) + foreground `CallService` |
| Media | Coil (images), Media3/ExoPlayer (in-app playback) |

Modules: `:app`, `:core-common`, `:core-ui`, `:core-crypto`, `:core-database`, `:core-network`, `:feature-{chat,calls,contacts,settings}`. Domain interfaces (`UserPrefsRepository`, `AccountRepository`, crypto stores) live in `core-*` modules so features never depend on each other.

### Message flow (spec Phase 4–5)

```
send: UI → ChatThreadViewModel → ChatRepository.send()
        1. insert MessageEntity(status=PENDING)          (encrypted DB)
        2. DIRECT:  SessionCipher (X3DH on first message, then Double Ratchet)
           GROUP:   ensure sender key → pairwise distribute (over 1:1 sessions)
                    → encrypt once with Sender Key (O(1)/message)
        3. envelope.send over WebSocket → status=SENT
        4. ack.delivered / ack.read → DELIVERED / READ

recv: SignalingClient.events → IncomingProcessor
        1. decrypt (prekey handshake transparent on first message)
        2. insert MessageEntity(status=RECEIVED), bump unread
        3. ack.delivered → sender   (read ack honors read-receipt pref)
```

Server-observable data: sender ID, recipient ID, size, timing, **never content** — the `payload` field is an opaque string the gateway neither parses nor stores (bounded, TTL'd offline queue only).

### Adaptive low-bandwidth calling

`LowBandwidthGovernor` samples `getStats()` every 2 s (RTT, available outgoing bitrate, loss) and walks a bitrate ladder `2500 → 1200 → 600 → 300 → 150 → 80 kbps`, rewriting the video sender's `maxBitrateBps`. Audio keeps priority; the call steps down instead of dropping. Status surfaces in the in-call UI ("adaptive bitrate step n · rtt").

### Group calls

Launch target 8–12 participants over an SFU (`call.join` room signaling is implemented; mediasoup/LiveKit fan-out is the media-tier deployment step, Phase 8). The client's `CallManager` handles the mesh path (2–4 participants) with the same signaling types; the SFU adapter replaces remote-track fan-out beyond that.

## Backend

Node 22 + TypeScript, one image per-service entrypoints (compose/k8s). All services are **stateless**; state is Postgres (metadata only) + Redis (presence/queues). A Redis pub/sub bus fans out across signaling replicas.

| Service | Port (dev) | Responsibility |
| --- | --- | --- |
| auth | 8081 | Ara ID creation, optional SMS-OTP (scrypt-hashed, rate-limited), JWT sessions + refresh rotation, prekey bundle serving (pops one-time prekeys) |
| signaling | 8082 | WS relay: `envelope.send/receive`, `call.*`, `ack.*`, `presence.*`; offline queue (100 env, 7-day TTL) of opaque blobs; per-connection token bucket |
| presence | 8083 | online/last-seen honoring per-user `status_visibility`; transient typing state |
| file-relay | 8084 | encrypted chunk upload/download; chunks hidden until `complete`; purge on delivery ack or 7-day TTL; 4 MiB chunk cap |
| push | 8085 | FCM **data-only** wake-ups (`{t:"wake"}`) — the client shows "new message" without content |

Datastores: PostgreSQL tables per spec §5 (`users`, `sessions`, `groups`, `group_members`, `devices`, `blocked_users`, `one_time_prekeys`) — **no message-content table exists** (CI grep-enforced). Redis: presence/session state in production.

Infra: NGINX ingress with WebSocket timeouts, HPA on the signaling tier, coturn TURN/STUN cluster (`deploy/turnserver.conf`, relay-port restrictions enabled).

## Protocol

JSON-over-WebSocket envelope (spec §3/§6):

```json
{
  "t": "envelope.send",
  "envelope": {
    "id": "ack-correlated relay id",
    "sender": "ARA-XXXX-XXXX-XXXX",
    "recipient": "ARA-YYYY-YYYY-YYYY",
    "payload": "<opaque: serialized WireMessage/SenderKeyWire — contains ciphertext+nonce>",
    "type": "1to1 | group",
    "timestamp": 1730000000000,
    "v": 1
  }
}
```

Call SDP/ICE traverse the same socket as opaque signaling payloads (`call.offer/answer/ice/end/ringing/decline/join`).
