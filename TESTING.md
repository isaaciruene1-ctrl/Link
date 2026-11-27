# LINK Testing & Verification Guide

## Environment Disclosure
This app was built in a cloud Android build environment without a local ADB device or live Android emulator daemon (`no adb` / `no instrumented tests`). All automated verification in this workspace ran on:
1. Local Node.js Firestore Emulator (`127.0.0.1:8085`) for security rules.
2. Local JVM unit tests + Robolectric (`sdk = 36`) via `gradle :app:testDebugUnitTest`.
3. Full Android APK debug build compilation via Gradle (`compile_applet`).

Live multi-device radio interactions (Wi-Fi NSD, BLE advertising/scanning, LAN socket transfers, `MediaProjection` casting, and Credential Manager Google Sign-In UI) **must be verified on two physical Android devices** as outlined below.

---

## Automated Verification Suite

### 1. Firestore Security Rules (`firestore.test.js`)
- Executed against `firebase emulators:start --only firestore` on port `8085` targeting database `ai-studio-android-link-5d9ffd22-c1d9-4774-b7b0-76882c19a3d1` (`5/5` passing):
  - Denies unauthenticated reads/writes on `/users/{uid}`.
  - Allows owner read/write on `/users/{uid}/devices/{deviceId}` and `/users/{uid}/handoffs/{handoffId}`.
  - Denies cross-user read/write between User A and User B.
  - Allows workspace member read/write and blocks non-member access on `/workspaces/{workspaceId}`.
  - Enforces owner isolation, `signalType` validation, and immutable payload updates on `/users/{uid}/relaySignals/{signalId}`.

### 2. JVM Security, Transfer & Continuity Tests (`LinkSecurityAndContinuityTest.kt`)
- `DeviceIdentityManager`: Keypair generation, deterministic `lnk_` ID derivation, `SHA256withECDSA` sign/verify (and tamper rejection), ephemeral ECDH `AES/GCM/NoPadding` encryption/decryption, monotonic sequence replay attack rejection (`SecurityException`), E2EE Cloud Relay secret derivation & AAD/wrong-key rejection, `WireFrameType.SCREEN_H264_FRAME` round-trip, symmetric 6-digit SAS derivation, and `LINK1|...` QR payload round-trip.
- `SensitiveDataDetector`: Blocks OTP codes, `AIza...` / `sk-...` / `Bearer` tokens, `password=` strings, and 16-digit card numbers while permitting normal URLs and notes.
- `FileTransferEngine`: Verified SHA-256 streaming file digest accuracy and `formatBytes` formatting.

### 3. Robolectric Android Integration Tests (`ExampleRobolectricTest.kt`)
- Verifies `app_name == "LINK"` and `firestore_database_id` resource resolution.
- Verifies Room `LinkDatabase` v2 persistence (`TrustedDeviceEntity` with `relaySecretBase64` insert/query/delete) using real SQLite under Robolectric.
- Verifies `FeatureStatusCatalog` includes Wi-Fi Direct, Cloud Remote Relay, H.264, and Multi-Control modules.
- Verifies Compose `AuthScreen` and `MultiControlDockCard` render key interactive tags cleanly.

---

## Physical Two-Device Hardware Test Protocol (Required Outside Cloud Container)
1. **Install Debug APK** on two Android 8.0+ devices (e.g., Pixel Phone + Galaxy Tablet) connected to the same Wi-Fi SSID (AP isolation disabled).
2. **Sign In**: Complete Google Sign-In on both devices using the same Google account.
3. **Discovery & Pairing**: Open **Home -> Scan Nearby** on both devices, confirm NSD/BLE discovery appears, tap **Pair**, and verify matching 6-digit SAS codes (or scan QR).
4. **Wi-Fi Direct & Cloud Relay**: Test **Scan P2P** / **Create Group** in Home, then disconnect Phone from local Wi-Fi (switch to cellular) and tap **Signal / Connect** or **Wake Ping** in Cloud Remote Relay.
5. **Encrypted File Transfer**: Pick a >10 MB video via Photo Picker and send it from Phone to Tablet; verify progress bar speed/ETA and `SHA-256 Verified` status in **Transfer**.
6. **Clipboard, Media Remote & Notifications**: Send clipboard text, inspect **Media Continuity & Remote** (`Refresh`, `Play/Pause`, `Vol+`, `Mute`, `Ring Locate Chime`), and verify Notification Sync dismiss.
7. **H.264 Screens & Multi-Control**: Start **Screens -> H.264 Hardware (MediaCodec)** projection, verify live `SurfaceView` rendering on Tablet, enable `LINK` Accessibility Service on Phone, arm **Allow Remote Input**, and send touchpad taps, swipes, Back/Home/Recents, and virtual text.
