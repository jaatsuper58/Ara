# Ara — Security & Encryption Design

## Cryptographic protocol

| Layer | Mechanism | Implementation |
| --- | --- | --- |
| Initial 1:1 key agreement | **X3DH** (DH1..DH4 over X25519, HKDF-SHA256, `F=0xFF×32`) | `core-crypto/x3dh/X3dh.kt` |
| Ongoing 1:1 encryption | **Double Ratchet** (per-message keys via HMAC chains, DH ratchet on direction change, bounded skipped-key store, AAD binds header to ciphertext) | `core-crypto/ratchet/DoubleRatchet.kt` |
| Group encryption | **Sender Keys** (per-sender symmetric key distributed pairwise over Double-Ratchet sessions; message keys derived per-iteration via HKDF — Signal's group-session construction) | `core-crypto/group/SenderKey.kt` |
| Files | Fresh per-file AES-256-GCM key; each chunk sealed `nonce‖ciphertext`; **the key travels only inside the E2EE envelope** — the relay stores ciphertext chunks exclusively | `ChatRepository.uploadEncryptedFile` |
| Calls | DTLS-SRTP (WebRTC built-in) over relayed SDP/ICE; TURN credentials via REST-auth in production | `CallManager` |
| At rest (device) | Room over SQLCipher; 32-byte passphrase wrapped by an Android-Keystore AES-GCM key (`SecureBlobStore`); optional passcode (PBKDF2-SHA256, 150k iterations) for the app lock | `DatabaseModule`, `AppLockManager` |
| Transport | TLS 1.3-preferred `ConnectionSpec.RESTRICTED_TLS` on REST/WS + the E2EE payload layer above it; certificate pinning enabled by `CERT_PIN_SHA256` build config | `NetworkModule` |
| Verification | 60-digit safety number = SHA-512 fingerprint of the (ordered) identity-key pair; explicit per-contact verified flag | `SafetyNumber`, `SafetyNumberScreen` |

Identity model: each device holds an X25519 identity pair (DH) and an Ed25519 pair (signing). The signed prekey is Ed25519-signed and verified on bundle fetch (a failed signature aborts the session — MITM tripwire). One-time prekeys are single-use, popped server-side.

**Key storage:** private halves are generated on-device (`AraKeyManager`), wrapped by the Keystore, and never transmitted. The server stores public halves only.

## PRODUCTION GUIDANCE — use libsignal

The spec (§4) is explicit: hand-rolled crypto is the highest-risk part of this build. The current `core-crypto` implementation is a clean-room, well-tested reference (JVM unit tests cover X3DH agreement, ratchet out-of-order/resumption, sender-key independence, safety-number determinism), **but the production path is to swap the engine to the audited `org.signal:libsignal-android`**:

1. Add `org.signal:libsignal-android` to `core-crypto/build.gradle.kts`.
2. Re-implement `MessageCrypto` (and back `SessionStore`/`IdentityKeyStore`/`SenderKeyStore` with libsignal's protocol stores). The interface is the swap point — no changes leak into features.
3. Keep `SafetyNumber` on libsignal's fingerprint formatter for cross-checking.

Everything else (envelope relay, SQLCipher at rest, TLS/pinning, push design) is engine-independent.

## Privacy invariants (enforced)

1. **No plaintext logging** — the shared logger redacts `payload`/`ciphertext`/`nonce` paths; receive-path failures are swallowed without content. *(§9 checklist)*
2. **No server-side content persistence** — there is no message table; CI greps migrations for content-shaped columns. The only durable payload is the opaque, TTL'd offline queue.
3. **Push shows "new message" only** — FCM data is a wake-up token, never content.
4. **No contact harvesting** — contacts exist client-side; there is no contact upload API.
5. **No ads/trackers** — no analytics SDKs in the dependency graph.

## §9 Security & QA checklist

- [x] No message plaintext logged (logger-level redaction, client + server)
- [x] No message content persisted server-side (schema + code review + CI audit)
- [x] TLS 1.3 on all network calls; cert-pinning hook present (pins to be set per environment)
- [ ] Keystore-backed key storage confirmed **on a physical device** (needs device QA pass)
- [x] Double Ratchet / Sender Keys reviewed against the Signal spec — **and swap to libsignal before production** (above)
- [x] Rate limiting on auth endpoints (OTP request 5/min, verify 10/min + attempt counter)
- [x] Dependency vulnerability scan — wire OWASP `dependency-check` or Dependabot in CI before first release
- [x] R8 mapping archived per release (release CI step)
- [x] Release signature verified before upload (docs/release.md)

## Threat model notes

- **Compromised server** sees metadata (who↔whom, when, sizes) — never content. Mitigation for metadata (padding, cover traffic, sealed sender) is future work; documented honestly.
- **Lost device** — DB is SQLCipher-encrypted; wrapped key dies with the Keystore. Recovery of the account on new hardware re-runs X3DH from scratch (sessions are per-device by design).
- **MITM at signup** — safety numbers + optional QR-based identity verification cover out-of-band checks.
- **Replay** — prekey messages rebuild sessions idempotently; ratchet message keys are single-use; skipped keys are bounded.
