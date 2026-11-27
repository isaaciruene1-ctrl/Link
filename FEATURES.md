# LINK Feature Status Ledger

Every capability uses one of the seven explicit honesty statuses reflected in `FeatureStatusCatalog`:
1. `Fully implemented and tested`
2. `Implemented but not fully tested`
3. `Partially implemented`
4. `Requires additional Android permissions`
5. `Device-dependent`
6. `Experimental`
7. `Not implemented`

| Module | Capability | Status | Verification & Notes |
|---|---|---|---|
| **LINK Core & Security** | AndroidKeyStore ECDSA identity, ECDH/AES-GCM sessions, E2EE relay keys, replay nonces | **Implemented but not fully tested** | Unit-tested on JVM (sign/verify, AES-GCM, E2EE relay AAD, replay rejection, SAS, QR); live two-device LAN handshake requires physical hardware. |
| **Discovery & Pairing** | Wi-Fi NSD (`_linkeco._tcp.`), BLE beacon scan/advertise, QR & 6-digit SAS pairing | **Implemented but not fully tested** | Real `NsdManager` and `BluetoothLeScanner` APIs; BLE requires Bluetooth runtime permission grant and physical radios. |
| **Wi-Fi Direct (P2P)** | `WifiP2pManager` peer discovery, Group Owner negotiation, direct P2P TCP handoff | **Device-dependent** | Real `WifiP2pManager` APIs implemented; hardware P2P group formation depends on OEM Wi-Fi chipsets. |
| **File Transfer Engine** | Chunked 64KB streaming I/O, Photo Picker & SAF, SHA-256 verification | **Implemented but not fully tested** | Streaming checksum and byte formatting verified in JVM tests; live LAN peer transfer pending physical hardware run. |
| **Universal Clipboard** | Foreground opt-in sync, sensitive OTP/key redaction, bounded 24h history | **Implemented but not fully tested** | `SensitiveDataDetector` pattern tests pass on JVM; respects Android 10+ background clipboard privacy model. |
| **Notification & Media Remote** | `NotificationListenerService` inbox, `MediaSessionManager` Now Playing, volume, mute & locate | **Requires additional Android permissions** | Requires explicit user toggle in Android Notification Access Settings (`BIND_NOTIFICATION_LISTENER_SERVICE`). |
| **Smart Handoff & Workspaces** | Intent/URL handoff cards, `ACTION_SEND` receiver, Firestore cloud workspaces | **Implemented but not fully tested** | Firestore security rules verified (`5/5` emulator tests passing); cloud sync active after Google Sign-In. |
| **Cloud Remote Relay** | Firestore SDP/ICE signaling, Wake Ping, and AES-GCM E2EE off-LAN relay packets | **Implemented but not fully tested** | Zero-trust owner-scoped Firestore rules tested on emulator; routes control/input when LAN socket is unavailable. |
| **Screens Mirroring (H.264)** | `MediaProjection` `VirtualDisplay` with H.264 `MediaCodec` & Adaptive JPEG fallback | **Experimental** | Hardware H.264 encoder/decoder + JPEG fallback implemented with system consent dialog; multi-device FPS pending physical run. |
| **Multi-Control Input Sharing** | Touchpad tap/swipe, virtual keyboard, Back/Home/Recents via opt-in Accessibility | **Requires additional Android permissions** | Gated behind explicit Accessibility Service opt-in, trusted peer flag, and emergency disarm switch. |
| **Gemini AI Assistant** | Firebase AI Logic (`gemini-3.8-flash`) structured action proposals | **Implemented but not fully tested** | Secured via App Check; proposed actions require explicit user confirmation before mutating state. |
| **OEM Background Matrix** | Cross-OEM battery exemption diagnostics & automated device lab matrix | **Not implemented** | Scheduled for Phase 6 ecosystem polish. |
