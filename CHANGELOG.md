# Changelog

## [0.6.0] — Phase 5: Input Sharing & Multi-Control
### Added
- `LinkInputAccessibilityService` & `InputControlEngine`: Opt-in gesture injection (tap & swipe via `dispatchGesture`), global navigation actions (`BACK`, `HOME`, `RECENTS`), and focused-field virtual keyboard text injection.
- `MultiControlDockCard`: Interactive touchpad, navigation buttons, text box, receiver arming switch, Accessibility status check, and emergency **"Stop Remote Control"** disarm banner.

## [0.5.0] — Phase 4: Cloud Remote Relay & Presence
### Added
- `RemoteRelayEngine` and `/users/{userId}/relaySignals/{signalId}` Firestore schema/rules/emulator test (`5/5` passing).
- Client-side `AES/GCM/NoPadding` E2EE relay encryption (`DeviceIdentityManager.deriveRelaySecretFromSession`, `encryptRelayPayload`, `decryptRelayPayload`) persisted on `TrustedDeviceEntity` (Room `v2`).
- Automatic LAN-first routing with off-LAN E2EE Cloud Relay fallback plus `OFFER`/`ANSWER`/`WAKE_PING` signaling in `CloudRelayDevicesSection`.

## [0.4.0] — Phase 3: High-Throughput Direct & Media Continuity
### Added
- `WifiP2pEngine`: Real `WifiP2pManager` peer scan, Group Owner creation/leave, and direct P2P TCP handoff.
- `ScreenMirrorEngine` H.264 pipeline: Hardware H.264 `MediaCodec` encoder (`createInputSurface()`) and `SurfaceView` decoder (`WireFrameType.SCREEN_H264_FRAME`) with Adaptive JPEG fallback.
- `MediaAndRemoteEngine`: `MediaSessionManager` `NowPlayingSnapshot` sync, `MUTE` / transport controls, and `MediaRemoteCard` in Continuity.

## [0.3.0] — Phase 2: Core Continuity & Screens Foundation
### Added
- **File Transfer Engine**: 64KB chunked encrypted socket transfer, Photo Picker & SAF pickers, SHA-256 verification, pause/cancel/retry, and Room transfer history.
- **Universal Clipboard**: Opt-in foreground clipboard sync, Android 13+ `EXTRA_IS_SENSITIVE` check, `SensitiveDataDetector`, and 24-hour Room auto-expiration.
- **Notification Sync**: `LinkNotificationListenerService` with remote dismissal and sensitive message/OTP filtering.
- **Smart Handoff & Automation**: `ACTION_SEND` text/URL receiver, handoff cards, automation rules, and remote media/ping/brightness controls.
- **Screens Mirroring (Experimental)**: Explicit-consent `MediaProjection` + `VirtualDisplay` LAN JPEG streamer and live viewer.
- **Gemini AI Assistant**: Firebase AI Logic (`gemini-3.8-flash`) structured continuity action proposals secured by App Check.

## [0.2.0] — Phase 1: Real Local Discovery & Encrypted Pairing
### Added
- `NsdDiscoveryEngine` (`_linkeco._tcp.`) and `BleDiscoveryEngine` (proximity BLE scanner/advertiser).
- `DeviceIdentityManager`: Keystore ECDSA P-256 identity, ephemeral ECDH `secp256r1`, `AES/GCM/NoPadding` + `HmacSHA256` session cipher with replay protection, 6-digit SAS, and ZXing QR pairing.
- `LinkConnectivityService` foreground service and per-peer trust management.

## [0.1.0] — Phase 0: Project Foundation
### Added
- `LINK` branding (`com.aistudio.linkecosystem.k7v9xm`), adaptive Deep Charcoal + Electric Cyan Material 3 theme, custom adaptive launcher icon, and `Space Grotesk` / `Plus Jakarta Sans` / `JetBrains Mono` typography.
- Room database, DataStore preferences, Google Sign-In via Credential Manager, and named Firestore database with verified zero-trust security rules.
