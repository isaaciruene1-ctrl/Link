# LINK Device Compatibility Matrix

## SDK Targets
- **Minimum SDK**: `26` (Android 8.0 Oreo) — ensures `NotificationChannel`, `AndroidKeyStore` ECDSA P-256 hardware-backed keys, `JobScheduler`/FGS APIs, and modern `java.nio` / `Cipher` support.
- **Target & Compile SDK**: `36` (Android 16) — enforces edge-to-edge insets, typed foreground services, Android 13–16 runtime permission separation, and Photo Picker compliance.

## Android Version Behavior Differences

| Android Version | API | Discovery & Permissions | Foreground Services & Media |
|---|---|---|---|
| **Android 8.0 – 10** | 26–29 | Legacy `BLUETOOTH`, `BLUETOOTH_ADMIN`, and `ACCESS_FINE_LOCATION` required for BLE scans (`<31`) & Wi-Fi Direct (`<33`). | Standard `startForegroundService`; background clipboard read restricted starting Android 10 (API 29). |
| **Android 11** | 30 | Legacy BLE + Wi-Fi Direct location gate (`maxSdkVersion="32"`). | Scoped storage + SAF document picker for file sharing; `AccessibilityService.dispatchGesture` supported. |
| **Android 12 – 12L** | 31–32 | Switches BLE to `BLUETOOTH_SCAN`/`CONNECT`/`ADVERTISE`; `ACCESS_FINE_LOCATION` retained strictly for `WifiP2pManager` on API 31–32. | Foreground service launch restrictions from background; `LinkConnectivityService` starts only from active foreground user flow. |
| **Android 13** | 33 | Adds `NEARBY_WIFI_DEVICES` (`neverForLocation`) for NSD & Wi-Fi Direct and `POST_NOTIFICATIONS`. Location no longer requested. | Respects `ClipDescription.EXTRA_IS_SENSITIVE` clipboard redaction flag. Photo Picker active natively. |
| **Android 14 – 16** | 34–36 | Enforces typed foreground services (`connectedDevice\|dataSync`, `mediaProjection`). | `MediaProjection` enforces per-session consent prompt; H.264 `MediaCodec` falls back automatically to Adaptive JPEG if encoder initialization fails. |

## Form-Factor Adaptivity
- **Compact Phones (<600dp)**: Bottom `NavigationBar` with 5 destinations, single-column stacked cards, `widthIn` constrained dialogs.
- **Medium / Foldables (600–839dp)**: Side `NavigationRail` with spacious padding and responsive grids.
- **Expanded Tablets / ChromeOS (>=840dp)**: Side `NavigationRail` + adaptive `widthIn(max = 840.dp)` workspace containers (`Home`, `Continuity`, `Screens`, `Transfer`, `Settings`).

## OEM Caveats
- **Samsung One UI / Xiaomi HyperOS / Oppo ColorOS / OnePlus OxygenOS**: Aggressive battery optimization can kill background `NsdManager` registrations or `LinkConnectivityService` sockets when screen-off. Wi-Fi Direct Group Owner negotiation speeds also vary across OEM Wi-Fi chipsets.
- **Corporate / Guest Wi-Fi**: Networks with AP/client isolation block mDNS multicast (`_linkeco._tcp.`) and direct TCP sockets; `RemoteRelayEngine` automatically routes control/media/input commands over E2EE Cloud Relay when opted in.
