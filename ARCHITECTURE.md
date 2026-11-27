# LINK Architecture

## Overview
LINK is a native Kotlin and Jetpack Compose Android ecosystem platform built with Clean Architecture + MVVM layering, local-first networking, encrypted peer sessions, and optional Firebase cloud continuity.

## Layering & Package Structure (`com.example`)
- **UI & Presentation (`ui/`, `MainActivity.kt`)**
  - `LinkRootApp` / `LinkAppShell`: Adaptive navigation (`NavigationBar` on `Compact`, `NavigationRail` on `Medium`/`Expanded`) across 5 primary destinations: Home, Continuity, Screens, Transfer, Settings.
  - `LinkViewModel`: Central reactive orchestrator exposing `StateFlow` UI state via `collectAsStateWithLifecycle`.
  - `FeatureStatusCatalog`: Transparent runtime feature ledger tracking implementation and verification maturity.
- **Core & Dependency Wiring (`core/`, `di/`, `LinkApplication.kt`)**
  - `LinkAppContainer`: Manual constructor-injection graph scoped to the application lifecycle.
  - `AppCheckHelper`: Initializes Firebase App Check (`PlayIntegrity` / `DebugAppCheckProviderFactory` + reCAPTCHA fallback) at `MainActivity.onCreate()` before UI or ViewModels.
  - `PermissionHelper`: Version-aware Android 8–16 runtime permission resolver and settings launcher.
  - `SystemTelemetryProvider`: Reads authentic local device telemetry (battery, Wi-Fi/cellular transport, form factor).
- **Security (`security/DeviceIdentityManager.kt`)**
  - Hardware-backed `AndroidKeyStore` ECDSA P-256 identity keypair (with software EC fallback for JVM unit tests).
  - Ephemeral `secp256r1` ECDH key agreement + SHA-256 transcript KDF -> `AES/GCM/NoPadding` + `HmacSHA256` `EncryptedSessionCipher` plus derived `relaySecretBase64` for off-LAN E2EE cloud relay packets.
  - Monotonic sequence number anti-replay protection on every encrypted LAN frame and AAD-bound GCM tags on cloud relay packets.
  - 6-digit Short Authentication String (SAS) and `LINK1|...` QR pairing payload encoder/decoder via ZXing.
- **Connectivity Engine (`connectivity/`)**
  - `NsdDiscoveryEngine`: Real `NsdManager` service registration and discovery (`_linkeco._tcp.`).
  - `BleDiscoveryEngine`: Real `BluetoothLeScanner` / `BluetoothLeAdvertiser` proximity discovery under custom UUID `7A9F2E10-4C8B-4B7E-9A1D-3E8C5F1B0001`.
  - `WifiP2pEngine`: Real `WifiP2pManager` peer scan, Group Owner negotiation, and direct P2P IP handoff (`connectToDirectHost`).
  - `RemoteRelayEngine`: Off-LAN Firestore signaling (`OFFER`, `ANSWER`, `ICE_CANDIDATE`, `WAKE_PING`) and E2EE AES-GCM `ENCRYPTED_RELAY_PACKET` fallback via `sendSmartControlMessage`.
  - `ConnectivityManagerEngine`: Transport abstraction unifying LAN discovery, Wi-Fi Direct handoff, TCP encrypted framing (`LinkProtocol` including `SCREEN_H264_FRAME`), ECDH handshake verification, heartbeat monitoring, and trust gating.
  - `LinkConnectivityService`: `connectedDevice|dataSync` foreground service keeping active LAN sockets alive while user-enabled.
- **Continuity, Transfers, Screens, Input & AI**
  - `transfer/FileTransferEngine.kt`: 64KB chunked streaming over encrypted LAN socket, `OpenableColumns` metadata, Photo Picker / SAF URI streaming, SHA-256 integrity verification, pause/cancel/retry.
  - `continuity/UniversalClipboardEngine.kt`: Foreground opt-in clipboard sharing, Android 13+ `EXTRA_IS_SENSITIVE` respect, `SensitiveDataDetector`, 24-hour auto-expiring Room cache.
  - `continuity/LinkNotificationListenerService.kt`: Real `NotificationListenerService` forwarding non-sensitive notifications to trusted peers with remote dismiss support.
  - `continuity/SmartHandoffEngine.kt`: URL/text/file handoff cards and `ACTION_SEND` receiver.
  - `continuity/MediaAndRemoteEngine.kt`: `MediaSessionManager` active media reading (`NowPlayingSnapshot`), transport control (`PLAY_PAUSE`, `NEXT`, `PREV`, `VOL_UP`, `VOL_DOWN`, `MUTE`), and Locate chime routed LAN-first with E2EE Cloud Relay fallback.
  - `screens/ScreenMirrorEngine.kt` & `LinkScreenShareService.kt`: Explicit user-consent `MediaProjection` + `VirtualDisplay` supporting hardware H.264 `MediaCodec` (`createInputSurface()` / `SurfaceView`) and Adaptive JPEG fallback (`mediaProjection` FGS).
  - `input/LinkInputAccessibilityService.kt` & `input/InputControlEngine.kt`: Opt-in Accessibility gesture injection (tap, swipe, Back/Home/Recents, focused-field text input) gated behind arming + per-peer `allowRemoteControl` trust.
  - `ai/LinkAssistantRepository.kt`: Firebase AI Logic (`Firebase.ai` with `gemini-3.8-flash`) producing structured JSON automation/continuity action proposals that require user confirmation before execution.
- **Data Layer (`data/local/`, `data/cloud/`, `auth/`)**
  - **Room (`LinkDatabase`, v2)**: `TrustedDeviceEntity` (including `relaySecretBase64`), `TransferRecordEntity`, `ClipboardEntryEntity`, `SyncedNotificationEntity`, `ActivityLogEntity`, `AutomationRuleEntity`.
  - **DataStore (`LinkPreferencesRepository`)**: User opt-in toggles, display name, theme preference, and retention controls.
  - **Firebase Auth (`GoogleAuthManager`)**: Credential Manager `GetSignInWithGoogleOption` authentication.
  - **Cloud Firestore (`CloudEcosystemRepository`)**: Named database (`ai-studio-android-link-5d9ffd22-c1d9-4774-b7b0-76882c19a3d1`) for multi-device registry, handoffs, relay signals, and workspace collaboration when signed in and opted in.

## Data Routing Policy
1. **Local-First**: Discovery, pairing handshake, files, clipboard pushes, notification mirrors, and screen frames route directly over local Wi-Fi or Wi-Fi Direct sockets (`ConnectivityManagerEngine`) without passing through cloud storage.
2. **E2EE Cloud Relay Fallback**: When a peer is unreachable on LAN and the user has enabled cloud sync, small metadata handoffs, media/locate commands, signaling offers, and Multi-Control packets route via `/users/{uid}/relaySignals/{signalId}` encrypted end-to-end with AES-GCM under strict `ownerUid` rules.
