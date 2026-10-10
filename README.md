# Ara

Ara is a **private, end-to-end encrypted Android messenger**: chat, voice/video calls, groups, and file sharing — with **no phone number required at signup**, **on-device-only message storage**, and a server tier that relays encrypted envelopes without ever seeing content.

Feature parity targets [Zangi](https://zangi.com)'s differentiators (privacy-first signup, E2EE everywhere, low-data calling, fast file transfer), implemented on a modern Android stack.

## Product principles

1. **No phone number, no SIM** — signup generates an Ara ID (`ARA-XXXX-XXXX-XXXX`). An optional SMS-OTP path exists for those who want it. **No contact-list upload, ever** — people connect by Ara ID or QR code.
2. **Servers are dumb pipes** — they relay opaque encrypted envelopes and push wake-ups. There is no message-content table in the server schema (audited in CI).
3. **Durable data lives on the device** — the Room database is SQLCipher-encrypted with a Keystore-wrapped key.
4. **Calls degrade, they don't drop** — an adaptive bitrate governor steps video down a ladder as the link weakens before it ever gives up.

## Repository layout

```
android/                     Android app (Kotlin, Compose, Hilt, MVVM/Clean)
  app/                       Shell: navigation, onboarding, workers, push
  core-common/               Result types, prefs/account contracts, constants
  core-crypto/               X3DH + Double Ratchet + Sender Keys, Keystore
  core-database/             Room + SQLCipher (source of truth), DAOs, crypto stores
  core-network/              REST (Retrofit), WebSocket signaling client, TLS pinning
  core-ui/                   Material 3 theme + shared components
  feature-chat/              Chats, groups, E2EE pipeline, files, stickers
  feature-calls/             WebRTC (org.webrtc), low-bandwidth governor, call UI
  feature-contacts/          Ara ID / QR contacts — no upload
  feature-settings/          Privacy, app lock, safety numbers, export
backend/                     Node/TypeScript microservices (metadata-only)
  services/auth/             Ara IDs, optional OTP, JWT sessions, key bundles
  services/signaling/        WebSocket relay: envelopes, call SDP/ICE, acks
  services/presence/         Online/last-seen/typing (visibility-aware)
  services/file-relay/       Encrypted chunk cache — purged on delivery/TTL
  services/push/             FCM data-only wake-ups (content-free)
deploy/                      coturn config, k8s manifests
docs/                        Architecture, security model, API, build phases
```

## Quickstart

### Backend (dev stack)

```bash
cd backend
npm install
npm test          # 19 tests: auth, encrypted relay, file relay, privacy audits
npm run dev       # auth:8081 signaling:8082 presence:8083 file-relay:8084 push:8085
```

Or the full containerized stack (Postgres, Redis, coturn, all services):

```bash
JWT_SECRET=$(openssl rand -hex 32) docker compose up --build
```

### Android

1. Open `android/` in Android Studio (Ladybug+), JDK 17.
2. Run on an emulator/device — onboarding generates an Ara ID and uploads the X3DH key bundle.
3. To point the app at your backend, set `API_BASE_URL` / `WS_URL` in `android/core-network/build.gradle.kts` (buildConfigField).

`./gradlew assembleDebug` builds; Phase 11 release signing is pre-wired via `local.properties` or CI secrets — see `docs/release.md`.

## Security model (short version)

- **X3DH + Double Ratchet** (Signal-protocol-style) for 1:1 sessions; **Sender Keys** for groups — implemented in `core-crypto` with JVM unit tests. Production guidance: swap the engine to the audited `org.signal:libsignal-android` behind the same `MessageCrypto` interface (the swap point is documented in `docs/security.md`).
- **Room + SQLCipher**, passphrase wrapped by the Android Keystore; keys generated and held on-device only.
- **TLS 1.3** on all transports **plus** the E2EE payload layer; certificate pinning lands via a build-config pin.
- **Push shows only "new message"** — FCM data payloads carry a wake-up, never content.
- **Safety numbers** (60-digit fingerprints) for out-of-band MITM checks.

Full threat model + checklist: [`docs/security.md`](docs/security.md).

## Status

Phase-by-phase tracker against the build spec: [`docs/build-phases.md`](docs/build-phases.md). Backend is implemented and tested in this repo; the Android app compiles in CI (lint + JVM crypto tests + emulator instrumented tests are wired) — device DoD items (two-device E2EE exchange, 3G call simulation) are listed per phase.

## License / trademarks

Internal project. Zangi is referenced only as a feature benchmark; no affiliation.
