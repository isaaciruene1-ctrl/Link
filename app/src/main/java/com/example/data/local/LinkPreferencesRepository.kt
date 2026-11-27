package com.example.data.local

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.linkDataStore: DataStore<Preferences> by preferencesDataStore(name = "link_preferences")

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

data class LinkPreferences(
    val deviceDisplayName: String = Build.MODEL,
    val autoDiscoveryEnabled: Boolean = true,
    val clipboardSyncOptIn: Boolean = false,
    val clipboardSensitiveFilter: Boolean = true,
    val notificationSyncOptIn: Boolean = false,
    val hideSensitiveNotifications: Boolean = true,
    val cloudWorkspaceSyncOptIn: Boolean = true,
    val autoAcceptFromMyDevices: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColorEnabled: Boolean = false,
    val onboardingCompleted: Boolean = false,
    val customDownloadTreeUri: String? = null
)

class LinkPreferencesRepository(private val context: Context) {

    private object Keys {
        val DEVICE_NAME = stringPreferencesKey("device_display_name")
        val AUTO_DISCOVERY = booleanPreferencesKey("auto_discovery_enabled")
        val CLIPBOARD_SYNC = booleanPreferencesKey("clipboard_sync_opt_in")
        val CLIPBOARD_SENSITIVE_FILTER = booleanPreferencesKey("clipboard_sensitive_filter")
        val NOTIFICATION_SYNC = booleanPreferencesKey("notification_sync_opt_in")
        val HIDE_SENSITIVE_NOTIFS = booleanPreferencesKey("hide_sensitive_notifications")
        val CLOUD_SYNC = booleanPreferencesKey("cloud_workspace_sync_opt_in")
        val AUTO_ACCEPT_MY_DEVICES = booleanPreferencesKey("auto_accept_my_devices")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color_enabled")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_completed")
        val DOWNLOAD_TREE_URI = stringPreferencesKey("custom_download_tree_uri")
    }

    val preferencesFlow: Flow<LinkPreferences> = context.linkDataStore.data.map { prefs ->
        val rawTheme = prefs[Keys.THEME_MODE] ?: ThemeMode.DARK.name
        val theme = ThemeMode.entries.firstOrNull { it.name == rawTheme } ?: ThemeMode.DARK
        LinkPreferences(
            deviceDisplayName = prefs[Keys.DEVICE_NAME]?.takeIf { it.isNotBlank() } ?: Build.MODEL,
            autoDiscoveryEnabled = prefs[Keys.AUTO_DISCOVERY] ?: true,
            clipboardSyncOptIn = prefs[Keys.CLIPBOARD_SYNC] ?: false,
            clipboardSensitiveFilter = prefs[Keys.CLIPBOARD_SENSITIVE_FILTER] ?: true,
            notificationSyncOptIn = prefs[Keys.NOTIFICATION_SYNC] ?: false,
            hideSensitiveNotifications = prefs[Keys.HIDE_SENSITIVE_NOTIFS] ?: true,
            cloudWorkspaceSyncOptIn = prefs[Keys.CLOUD_SYNC] ?: true,
            autoAcceptFromMyDevices = prefs[Keys.AUTO_ACCEPT_MY_DEVICES] ?: false,
            themeMode = theme,
            dynamicColorEnabled = prefs[Keys.DYNAMIC_COLOR] ?: false,
            onboardingCompleted = prefs[Keys.ONBOARDING_DONE] ?: false,
            customDownloadTreeUri = prefs[Keys.DOWNLOAD_TREE_URI]
        )
    }

    suspend fun setDeviceDisplayName(name: String) {
        val cleaned = name.trim().take(60)
        if (cleaned.isNotBlank()) {
            context.linkDataStore.edit { it[Keys.DEVICE_NAME] = cleaned }
        }
    }

    suspend fun setAutoDiscovery(enabled: Boolean) {
        context.linkDataStore.edit { it[Keys.AUTO_DISCOVERY] = enabled }
    }

    suspend fun setClipboardSyncOptIn(enabled: Boolean) {
        context.linkDataStore.edit { it[Keys.CLIPBOARD_SYNC] = enabled }
    }

    suspend fun setClipboardSensitiveFilter(enabled: Boolean) {
        context.linkDataStore.edit { it[Keys.CLIPBOARD_SENSITIVE_FILTER] = enabled }
    }

    suspend fun setNotificationSyncOptIn(enabled: Boolean) {
        context.linkDataStore.edit { it[Keys.NOTIFICATION_SYNC] = enabled }
    }

    suspend fun setHideSensitiveNotifications(enabled: Boolean) {
        context.linkDataStore.edit { it[Keys.HIDE_SENSITIVE_NOTIFS] = enabled }
    }

    suspend fun setCloudWorkspaceSyncOptIn(enabled: Boolean) {
        context.linkDataStore.edit { it[Keys.CLOUD_SYNC] = enabled }
    }

    suspend fun setAutoAcceptFromMyDevices(enabled: Boolean) {
        context.linkDataStore.edit { it[Keys.AUTO_ACCEPT_MY_DEVICES] = enabled }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.linkDataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        context.linkDataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.linkDataStore.edit { it[Keys.ONBOARDING_DONE] = completed }
    }

    suspend fun setCustomDownloadTreeUri(uri: String?) {
        context.linkDataStore.edit { prefs ->
            if (uri.isNullOrBlank()) prefs.remove(Keys.DOWNLOAD_TREE_URI)
            else prefs[Keys.DOWNLOAD_TREE_URI] = uri
        }
    }
}
