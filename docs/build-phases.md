# Build-Phase Tracker (spec §8)

Status legend: ✅ implemented in-repo · 🟡 implemented, device/backend verification pending · ⬜ not started

| Phase | Scope | Status | Where / How to verify |
| --- | --- | --- | --- |
| **1 — Environment & project setup** | Modules, version catalog, Hilt app, CI | ✅ | `android/settings.gradle.kts`, `gradle/libs.versions.toml`, `.github/workflows/android.yml` (lint + unit tests + assembleDebug). *DoD "installs on emulator": open `android/` in Android Studio and Run ▶* |
| **2 — Local data layer** | Room entities, SQLCipher + Keystore key, DAOs/repositories | ✅ | `core-database`; instrumented DoD test `AraDatabaseTest` (insert/query + **on-disk file is not plaintext SQLite**) runs in CI on the emulator job |
| **3 — Auth & onboarding** | Ara-ID creation (no phone), X3DH identity generation + bundle upload, §7.1 screens | ✅ code / 🟡 staging DoD | `OnboardingViewModel` (keys → create account → session → prekey upload → notes-to-self chat); backend `auth.test.ts` proves the REST flow. *Two-device staging pass pending* |
| **4 — Core messaging (local-only)** | Chat list/thread UI wired to Room, send pipeline PENDING→SENT→DELIVERED→READ | ✅ | `feature-chat` (`ChatRepository`, `ChatThreadScreen`); status transitions unit-covered at repository level; local UI test against Room listed in `feature-chat` androidTest (stub) |
| **5 — Real-time sync** | Signaling+presence services, X3DH/DR client wiring, envelope relay + acks | ✅ backend / ✅ client / 🟡 two-device DoD | `test/signaling.test.ts` (relay, forged-sender rejection, offline queue+drain, acks, presence, **no-plaintext-persistence audit**); client `SignalingClient` + `IncomingProcessor`. *DoD "two devices exchange encrypted messages": build debug APK on two devices against the dev stack* |
| **6 — Media & files** | Chunked resumable encrypted transfer, relay, media viewer, retention purge | ✅ backend / ✅ client core / 🟡 50 MB throttled DoD | `FilesApi` + `uploadEncryptedFile/downloadEncryptedFile` (parallel chunked AES-GCM); `file-relay.test.ts` (round-trip byte-exact, purge-on-ack, caps, access gating); `RetentionWorker` auto-purge. *Throttled-transfer pass pending* |
| **7 — Voice & video calling** | org.webrtc, 1:1 signaling, in-call UI + foreground service, adaptive low-bandwidth | ✅ code / 🟡 3G-profile DoD | `feature-calls` (`CallManager`, `LowBandwidthGovernor`, `CallService`, `InCallScreen`). *DoD: stable 1:1 video on simulated 3G via Android Studio Network Profiler* |
| **8 — Groups & group calls** | Group CRUD+roles, Sender Keys, SFU group calls | 🟡 | CRUD+roles: REST + client repo wired; Sender Keys implemented+tested (`SenderKeyTest`); group calls: `call.join` room signaling + mesh path for 2–4; **SFU deployment (mediasoup/LiveKit) and 6-person×10 min DoD outstanding** |
| **9 — Privacy, hardening & polish** | App-lock, disappearing, safe mode, safety numbers, theming, stickers/GIF, a11y | 🟡 | App lock (biometric+PBKDF2 passcode) ✅, per-chat disappearing ✅ (`RetentionWorker`), safe mode (screenshot block flag + forced disappearing) ✅, safety numbers ✅, light/dark/system ✅. *Sticker packs/GIF picker and TalkBack audit pass outstanding* |
| **10 — Testing** | 80%+ on crypto/pipeline, Compose UI tests, QA vs §9 | 🟡 | Crypto: `core-crypto/src/test` (X3DH, ratchet incl. out-of-order+serialization+tamper, sender keys, safety numbers, session-cipher E2E over JSON wire). Backend: 19 tests. *Coverage gate + Compose UI tests + manual QA pass outstanding* |
| **11 — Release build** | Keystore, signingConfigs, R8, assembleRelease/bundleRelease | ✅ wired / ⬜ keystore ceremony | `app/build.gradle.kts` signing from `local.properties`/CI secrets (never committed); release CI job builds signed APK/AAB + archives R8 mapping. *Run `docs/release.md` steps with a real keystore* |

## Known gaps vs. the full spec (honest list)

- **Stickers/GIF picker**: reaction + picker UI scaffolding exists; no bundled sticker packs or Giphy integration yet.
- **Call forwarding & DND scheduling**: signaling types exist (`call.decline`, presence); the forwarding rules engine is not built.
- **Group call SFU**: media tier not deployed (mesh works at launch scale; `docs/architecture.md` notes the swap).
- **Screen share, in-call chat drawer**: UI hooks present, capture pipeline not wired.
- **Multi-device sync of history**: sessions are per-device by design (like the reference protocol); history re-sync is future work.
- **Android code compiles in CI only** — this workspace has no Android SDK; first local `gradle assembleDebug` may surface small API-level fixes.
