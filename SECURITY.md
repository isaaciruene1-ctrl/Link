# LINK Security Architecture

## 1. Cryptographic Device Identity (`DeviceIdentityManager`)
- Every device generates an asymmetric ECDSA P-256 (`secp256r1`) keypair inside `AndroidKeyStore` (`link_device_identity_v1`) when available.
- The canonical `deviceId` is deterministically derived from the SHA-256 digest of the X.509-encoded public key (`lnk_<24_hex_chars>`), preventing ID spoofing without key possession.
- Keypair authenticity is verified via `SHA256withECDSA` signatures over fresh handshake challenges.

## 2. Encrypted Transport Sessions (`EncryptedSessionCipher`)
- Each peer connection performs an ephemeral `secp256r1` ECDH key exchange bound to the handshake transcript (`SHA-256(sharedSecret || transcript)`).
- Derived keys:
  - 128-bit `AES/GCM/NoPadding` encryption key with random 96-bit IV per frame and 128-bit authentication tag.
  - 256-bit `HmacSHA256` subkey.
  - Derived 128-bit `relaySecretBase64` bound to sorted device IDs (`LINK_CLOUD_RELAY_V1`) stored locally for off-LAN E2EE cloud relay packets.
- **Anti-Replay Protection**: Each encrypted LAN frame carries a strictly increasing 64-bit sequence number bound as GCM Additional Authenticated Data (`AAD`). Frames with `seq <= highestIncomingSeq` throw `SecurityException` and are dropped.

## 3. Pairing Verification & Remote Input Gating
- **QR Payload (`LINK1|...`)**: Embeds `deviceId`, Base64 device name, LAN host, port, public key, and fresh cryptographic nonce.
- **Short Authentication String (SAS)**: Computes a deterministic 6-digit numeric code from sorted local/remote device IDs and ephemeral public keys so users can confirm no MITM occurred.
- **Multi-Control Input Safety**: `LinkInputAccessibilityService` sets `canRetrieveWindowContent="false"`, injects gestures only when `receiverArmed == true` AND the authenticated sender has `allowRemoteControl == true` in Room, and exposes an immediate emergency **"Stop Remote Control"** disarm control.
- **Trust Revocation**: Removing a peer in Device Management clears its Room trust record and immediately closes active sockets.

## 4. Sensitive Data Filtering
- **Clipboard (`SensitiveDataDetector` & Android 13+ `EXTRA_IS_SENSITIVE`)**: Clipboard items flagged by Android as sensitive or matching OTPs, API keys (`AIza...`, `sk-...`, `Bearer ...`), `password=`/`secret=`, or payment card patterns are blocked from syncing and masked locally.
- **Notifications (`LinkNotificationListenerService`)**: Skips low-priority ongoing foreground notifications and filters `CATEGORY_MESSAGE` / OTP / verification codes unless explicitly permitted.
- **File Transfers**: Streams compute end-to-end SHA-256 checksums (`computeUriSha256` / `computeSha256ForFile`) and reject mismatched incoming files.

## 5. Cloud & AI Security
- **Authentication**: Google Sign-In via Jetpack `CredentialManager` (`GetSignInWithGoogleOption`). Anonymous and unauthenticated Firestore access are denied.
- **Firestore Zero-Trust Rules (`firestore.rules`)**:
  - `users/{uid}` and subcollections (`devices`, `handoffs`, `preferences`, `relaySignals`) are readable/writable strictly when `request.auth.uid == uid`.
  - `relaySignals/{signalId}` enforces allowed `signalType` enum (`OFFER`, `ANSWER`, `ICE_CANDIDATE`, `WAKE_PING`, `ENCRYPTED_RELAY_PACKET`), immutable payloads on update, and `ENCRYPTED_RELAY_PACKET` payloads encrypted client-side with AES-GCM (`roomId|sender|target` AAD).
  - `workspaces/{workspaceId}` and `workspaces/{workspaceId}/items/{itemId}` enforce membership via `memberUids`.
- **App Check (`AppCheckHelper`)**: Configured at launch before ViewModels/SDK calls (`PlayIntegrity` in release, `DebugAppCheckProviderFactory` in debug) to protect Firebase AI Logic (`gemini-3.8-flash`) and Firestore.
- **Backup Disabled**: `android:allowBackup="false"` prevents extraction of local trust metadata.
