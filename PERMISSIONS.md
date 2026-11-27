# LINK Permissions & Privacy Architecture

LINK requests zero dangerous permissions on first launch. Permissions are requested on demand only when the user explicitly activates the associated feature, and no broad storage permissions (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`) are declared.

## Manifest Declarations (`app/src/main/AndroidManifest.xml`)

### 1. Normal / Install-Time Permissions (No Runtime Prompt)
- `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`: Required for local TCP sockets, mDNS/NSD discovery, Firebase Auth, Firestore sync, and Gemini AI Logic.
- `VIBRATE`: Tactile confirmation for pairing and remote ping actions.
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`: Required on Android 14+ (API 34+) for typed foreground services during active connectivity, file transfer, and screen casting.

### 2. Local Network, Wi-Fi Direct & Bluetooth Discovery (Version-Gated)
- `NEARBY_WIFI_DEVICES` (`android:usesPermissionFlags="neverForLocation"`): Requested on Android 13+ (API 33+) when scanning for local Wi-Fi NSD or Wi-Fi Direct P2P peers.
- `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`: Requested on Android 12+ (API 31+) only when starting BLE proximity discovery/advertising.
- `BLUETOOTH`, `BLUETOOTH_ADMIN`: Scoped strictly to Android 8.0–11 (API 26–30).
- `ACCESS_FINE_LOCATION` (`android:maxSdkVersion="32"`): Scoped to Android 8.0–12L (API 26–32), where Android requires location permission for BLE (<31) and Wi-Fi Direct `WifiP2pManager` (<33). Never requested on Android 13+.

### 3. Notifications, Accessibility & Foreground Service Visibility
- `POST_NOTIFICATIONS`: Requested on Android 13+ (API 33+) when starting foreground connectivity or screen-share notifications.
- `BIND_NOTIFICATION_LISTENER_SERVICE` on `LinkNotificationListenerService`: Special system setting (`Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`) enabled manually by the user for Notification Sync and `MediaSessionManager` active session reading.
- `BIND_ACCESSIBILITY_SERVICE` on `LinkInputAccessibilityService` (`canPerformGestures="true"`, `canRetrieveWindowContent="false"`): Special system setting (`Settings.ACTION_ACCESSIBILITY_SETTINGS`) enabled manually by the user only for opt-in Multi-Control gesture/navigation injection. Also requires explicit receiver arming and `allowRemoteControl` trust per peer.

### 4. Camera (Optional)
- `CAMERA` (`hardware.camera required="false"`): Requested only if the user opens QR pairing camera flow; numeric 6-digit SAS / manual host+QR payload entry always works without camera access.

### 5. Zero-Permission File & Media Access
- Uses Android Photo Picker (`ActivityResultContracts.PickMultipleVisualMedia`) for photos/videos and Storage Access Framework (`ActivityResultContracts.OpenDocument`) for documents. No gallery-wide read permissions are needed.

### 6. Screen Capture
- Uses system `MediaProjectionManager.createScreenCaptureIntent()` prompt per session with `LinkScreenShareService` (`foregroundServiceType="mediaProjection"`). Never captures silently.
