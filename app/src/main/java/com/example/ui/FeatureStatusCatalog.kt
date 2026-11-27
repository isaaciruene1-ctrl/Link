package com.example.ui

enum class FeatureStatus(val label: String) {
    FULLY_IMPLEMENTED_AND_TESTED("Fully implemented and tested"),
    IMPLEMENTED_NOT_FULLY_TESTED("Implemented but not fully tested"),
    PARTIALLY_IMPLEMENTED("Partially implemented"),
    REQUIRES_PERMISSIONS("Requires additional Android permissions"),
    DEVICE_DEPENDENT("Device-dependent"),
    EXPERIMENTAL("Experimental"),
    NOT_IMPLEMENTED("Not implemented")
}

data class FeatureLedgerItem(
    val module: String,
    val capability: String,
    val status: FeatureStatus,
    val note: String
)

object FeatureStatusCatalog {
    val items = listOf(
        FeatureLedgerItem(
            module = "LINK Core & Security",
            capability = "AndroidKeyStore ECDSA identity, ECDH/AES-GCM sessions, E2EE relay keys",
            status = FeatureStatus.IMPLEMENTED_NOT_FULLY_TESTED,
            note = "Crypto & replay rejection unit-tested on JVM; live two-device handshake pending physical hardware."
        ),
        FeatureLedgerItem(
            module = "Discovery & Pairing",
            capability = "Wi-Fi NSD (_linkeco._tcp.), BLE beacon scan/advertise, QR & 6-digit SAS pairing",
            status = FeatureStatus.IMPLEMENTED_NOT_FULLY_TESTED,
            note = "Real NsdManager and BluetoothLeScanner APIs; BLE requires Bluetooth runtime grant."
        ),
        FeatureLedgerItem(
            module = "Wi-Fi Direct (P2P)",
            capability = "WifiP2pManager peer discovery, Group Owner negotiation, direct P2P handoff",
            status = FeatureStatus.DEVICE_DEPENDENT,
            note = "Real WifiP2pManager APIs implemented; hardware P2P group formation varies by OEM Wi-Fi chipset."
        ),
        FeatureLedgerItem(
            module = "File Transfer Engine",
            capability = "Chunked 64KB streaming I/O, Photo Picker & SAF, SHA-256 checksum verification",
            status = FeatureStatus.IMPLEMENTED_NOT_FULLY_TESTED,
            note = "Streaming & SHA-256 verified in JVM tests; multi-device LAN transfer pending hardware verification."
        ),
        FeatureLedgerItem(
            module = "Universal Clipboard",
            capability = "Foreground opt-in sync, sensitive OTP/key redaction, bounded 24h history",
            status = FeatureStatus.IMPLEMENTED_NOT_FULLY_TESTED,
            note = "Compliant with Android 10+ background clipboard restrictions; manual foreground send."
        ),
        FeatureLedgerItem(
            module = "Notification & Media Remote",
            capability = "NotificationListenerService inbox, MediaSessionManager Now Playing, volume & locate",
            status = FeatureStatus.REQUIRES_PERMISSIONS,
            note = "Requires explicit user opt-in in Android Notification Access Settings."
        ),
        FeatureLedgerItem(
            module = "Cloud Remote Relay",
            capability = "Firestore SDP/ICE signaling, Wake Ping, and AES-GCM E2EE off-LAN relay packets",
            status = FeatureStatus.IMPLEMENTED_NOT_FULLY_TESTED,
            note = "Firestore rules verified (5/5 emulator tests); routes control/input when LAN socket is unavailable."
        ),
        FeatureLedgerItem(
            module = "Screens Mirroring (H.264)",
            capability = "MediaProjection VirtualDisplay with H.264 MediaCodec & Adaptive JPEG fallback",
            status = FeatureStatus.EXPERIMENTAL,
            note = "Hardware H.264 encoder/decoder + JPEG fallback implemented; live multi-device FPS pending physical run."
        ),
        FeatureLedgerItem(
            module = "Multi-Control Input Sharing",
            capability = "Touchpad tap/swipe, virtual keyboard, Back/Home/Recents via opt-in Accessibility",
            status = FeatureStatus.REQUIRES_PERMISSIONS,
            note = "Gated behind explicit Accessibility Service opt-in, trusted peer flag, and emergency disarm switch."
        ),
        FeatureLedgerItem(
            module = "OEM Background Matrix",
            capability = "Cross-OEM battery exemption diagnostics & automated device lab matrix",
            status = FeatureStatus.NOT_IMPLEMENTED,
            note = "Scheduled for Phase 6 ecosystem polish."
        )
    )
}
