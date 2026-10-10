# Release Guide (spec Phase 11)

## 1. Create the signing keystore (once, keep it safe)

```bash
keytool -genkey -v \
  -keystore ara-release.keystore -alias ara \
  -keyalg RSA -keysize 2048 -validity 10000
```

- Store the keystore + passwords in your password manager / HSM.
- **Never commit** the keystore or passwords (`.gitignore` already excludes `*.keystore`, `*.jks`, `keystore.properties`).
- For Play Store enrollment in Play App Signing, keep the upload key safe and let Google manage the release key.

## 2. Local signing config

Add to `android/local.properties` (or export env vars):

```properties
ara.keystore.path=/absolute/path/ara-release.keystore
ara.keystore.password=********
```

`app/build.gradle.kts` reads these (or `ARA_KEYSTORE_PATH` / `ARA_KEYSTORE_PASSWORD` / `ARA_KEY_ALIAS` / `ARA_KEY_PASSWORD` env vars) into the `release` signingConfig. If absent, release builds are unsigned-safe (CI skips signing).

## 3. Build

```bash
cd android
./gradlew assembleRelease     # → app/build/outputs/apk/release/app-release.apk
./gradlew bundleRelease       # → app/build/outputs/bundle/release/app-release.aab (Play upload)
```

Both run with `minifyEnabled true`, `shrinkResources true`, R8 rules in `app/proguard-rules.pro` (serialization, WebRTC JNI, SQLCipher, BouncyCastle kept).

## 4. Pre-upload verification (§9)

```bash
# Verify the signature
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk

# Install on a clean device (DoD)
adb install -r app/build/outputs/apk/release/app-release.apk

# Archive the R8 mapping (crash de-obfuscation) — CI does this automatically
cp app/build/outputs/mapping/release/mapping.txt mapping-$(git rev-parse --short HEAD).txt
```

Upload the `.aab` via Play Console; upload the mapping file with the release.

## 5. CI release job

`.github/workflows/android.yml` → `release` job (runs on `main`):
- `ARA_KEYSTORE_B64` (base64 of the keystore), `ARA_KEYSTORE_PASSWORD`, `ARA_KEY_ALIAS`, `ARA_KEY_PASSWORD` as environment secrets.
- The job builds `assembleRelease` + `bundleRelease`, archives the APK/AAB and the mapping file.

```bash
base64 -w0 ara-release.keystore > ara-release.keystore.b64   # paste into the secret
```

## 6. Environment hardening before first production release

1. Set `JWT_SECRET` (32+ random bytes) — never the dev default.
2. Set `CERT_PIN_SHA256` (SPKI SHA-256 of the leaf/intermediate) in `core-network` build config.
3. Point `API_BASE_URL` / `WS_URL` at production hosts.
4. Add `google-services.json` + the `com.google.gms.google-services` plugin for FCM.
5. Deploy TURN with REST-auth credentials; remove the dev static credential from `deploy/turnserver.conf`.
6. Run `./gradlew dependencyCheckAnalyze` (OWASP dependency-check plugin) and clear findings.
7. Confirm §9 checklist in `docs/security.md` — including the physical-device Keystore check.
