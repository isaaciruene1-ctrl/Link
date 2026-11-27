# LINK Development Roadmap

## Phase 0 — Project Foundation (Implemented)
- Kotlin + Jetpack Compose Material 3 app shell (`LINK`, `applicationId: com.aistudio.linkecosystem.k7v9xm`, `minSdk = 26`, `targetSdk = 36`).
- Dark-first Deep Charcoal (`#0B0E14`) + Electric Cyan (`#00E5FF`) adaptive theme with `Space Grotesk`, `Plus Jakarta Sans`, and `JetBrains Mono` typography.
- Adaptive `NavigationBar` / `NavigationRail` shell, custom adaptive launcher icon, and Privacy & Activity ledger.
- Room database (`LinkDatabase`), DataStore (`LinkPreferencesRepository`), Firebase Auth (Google Sign-In via Credential Manager), Firestore schema/rules/tests, and Firebase AI Logic (`gemini-3.8-flash`) with App Check.

## Phase 1 — Real Local Discovery & Encrypted Pairing (Implemented)
- `NsdDiscoveryEngine` (`_linkeco._tcp.`) and `BleDiscoveryEngine` (proximity BLE scan/advertise).
- `DeviceIdentityManager` with AndroidKeyStore ECDSA P-256 identity keys, ephemeral ECDH `secp256r1` handshake, `AES/GCM/NoPadding` + `HmacSHA256` framing, monotonic replay protection, 6-digit SAS confirmation, and `LINK1|...` QR pairing codes.
- Trusted device revocation, trust permissions per peer, and `LinkConnectivityService` foreground session lifecycle.

## Phase 2 — Core Continuity & Media/Screens Foundation (Implemented)
- **File Transfer Engine**: 64KB chunked streaming I/O, Android Photo Picker & SAF document selection, SHA-256 verification, pause/cancel/retry, and Room history.
- **Universal Clipboard**: Foreground opt-in clipboard sync, Android 13+ sensitive flag check, `SensitiveDataDetector` OTP/API-key/card filter, 24-hour expiration.
- **Notification Sync**: `LinkNotificationListenerService` inbox with sensitive notification filtering and remote dismiss.
- **Smart Handoff & Automation**: Intent/URL handoffs, `ACTION_SEND` integration, rule builder, and Gemini AI structured action suggestions.
- **Screens Mirroring (Foundation)**: Explicit-consent `MediaProjection` + `VirtualDisplay` LAN JPEG viewer/host (`mediaProjection` FGS).

## Phase 3 — High-Throughput Direct & Media Continuity (Implemented)
- **Wi-Fi Direct (`WifiP2pEngine`)**: `WifiP2pManager` peer discovery, Group Owner creation/leave, and automatic encrypted TCP peer handoff over the Group Owner IP.
- **Hardware-Accelerated Screens (`ScreenMirrorEngine`)**: Hardware H.264 `MediaCodec` encoder (`createInputSurface()`) & `SurfaceView` decoder (`SCREEN_H264_FRAME`) alongside Adaptive JPEG (`JPEG_ADAPTIVE`) fallback and configurable bitrate presets.
- **Rich Media Continuity (`MediaAndRemoteEngine`)**: `MediaSessionManager` `NowPlayingSnapshot` extraction (title, artist, playback state, music volume %), transport commands (`PLAY_PAUSE`, `NEXT`, `PREV`, `VOL_UP`, `VOL_DOWN`, `MUTE`), and Locate chime.

## Phase 4 — Cloud Remote Relay & Presence (Implemented)
- **Cloud Relay & Signaling (`RemoteRelayEngine`)**: Firestore `/users/{userId}/relaySignals/{signalId}` channel supporting `OFFER`, `ANSWER`, `ICE_CANDIDATE`, `WAKE_PING`, and E2EE `AES/GCM/NoPadding` `ENCRYPTED_RELAY_PACKET` frames derived from ECDH pairing secrets (`TrustedDeviceEntity.relaySecretBase64`).
- **Smart Transport Routing**: `sendSmartControlMessage` tries direct encrypted LAN / Wi-Fi Direct sockets first and seamlessly falls back to E2EE Cloud Relay when off-LAN and opted in.

## Phase 5 — Input Sharing & Multi-Control (Implemented)
- **Opt-In Input Bridge (`LinkInputAccessibilityService`, `InputControlEngine`)**: Accessibility gesture injection (`dispatchGesture` tap & swipe), global navigation actions (`BACK`, `HOME`, `RECENTS`), and focused-node text injection (with clipboard fallback).
- **Multi-Control Dock UI**: Interactive touchpad surface, remote navigation bar, virtual keyboard text input, strict `allowRemoteControl` + receiver arming gate, and prominent emergency **"Stop Remote Control"** disarm banner.

## Phase 6 — Ecosystem Polish & Hardware Verification Matrix (Planned)
- Multi-manufacturer (Pixel, Samsung One UI, Xiaomi HyperOS, OnePlus OxygenOS) background stability & battery-optimization diagnostics.
- Automated multi-device instrumentation test lab matrix.
