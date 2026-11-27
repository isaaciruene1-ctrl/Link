package com.example.continuity

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.RingtoneManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.view.KeyEvent
import com.example.connectivity.ConnectivityManagerEngine
import com.example.connectivity.RemoteRelayEngine
import com.example.core.PermissionHelper
import com.example.data.local.ActivityLogDao
import com.example.data.local.ActivityLogEntity
import com.example.data.local.TrustedDeviceDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

data class NowPlayingSnapshot(
    val sourceDeviceId: String = "",
    val sourceDeviceName: String = "",
    val title: String = "No active media session",
    val artist: String = "",
    val packageName: String = "",
    val isPlaying: Boolean = false,
    val volumePercent: Int = -1
)

class MediaAndRemoteEngine(
    private val context: Context,
    private val connectivityEngine: ConnectivityManagerEngine,
    private val remoteRelayEngine: RemoteRelayEngine,
    private val trustedDeviceDao: TrustedDeviceDao,
    private val activityLogDao: ActivityLogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val audioManager: AudioManager? by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    private val mediaSessionManager: MediaSessionManager? by lazy {
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    }

    private val _lastRemoteFeedback = MutableStateFlow<String?>(null)
    val lastRemoteFeedback: StateFlow<String?> = _lastRemoteFeedback.asStateFlow()

    private val _remoteNowPlaying = MutableStateFlow<NowPlayingSnapshot?>(null)
    val remoteNowPlaying: StateFlow<NowPlayingSnapshot?> = _remoteNowPlaying.asStateFlow()

    init {
        scope.launch {
            connectivityEngine.controlEvents.collect { event ->
                when (event.type) {
                    "MEDIA_CMD" -> handleIncomingMediaCommand(event.peerDeviceId, event.peerName, event.payload)
                    "LOCATE_CHIME" -> handleIncomingLocateChime(event.peerDeviceId, event.peerName)
                    "MEDIA_NOW_PLAYING" -> handleIncomingNowPlaying(event.peerDeviceId, event.peerName, event.payload)
                    "MEDIA_STATE_REQ" -> broadcastLocalMediaState(event.peerDeviceId)
                }
            }
        }
    }

    fun sendMediaCommand(targetDeviceId: String, command: String) {
        scope.launch {
            val route = remoteRelayEngine.sendSmartControlMessage(
                targetDeviceId,
                "MEDIA_CMD",
                JSONObject().put("command", command)
            )
            _lastRemoteFeedback.value = if (route != null) {
                "Sent '$command' via ${route.label}"
            } else {
                "Device offline — connect locally or enable Cloud Relay"
            }
        }
    }

    fun requestRemoteMediaState(targetDeviceId: String) {
        scope.launch {
            val route = remoteRelayEngine.sendSmartControlMessage(
                targetDeviceId,
                "MEDIA_STATE_REQ",
                JSONObject()
            )
            if (route == null) {
                _lastRemoteFeedback.value = "Unable to query media state — peer unreachable"
            }
        }
    }

    fun triggerRemoteLocateChime(targetDeviceId: String) {
        scope.launch {
            val route = remoteRelayEngine.sendSmartControlMessage(
                targetDeviceId,
                "LOCATE_CHIME",
                JSONObject()
            )
            _lastRemoteFeedback.value = if (route != null) {
                "Locate chime sent via ${route.label}"
            } else {
                "Device unreachable locally or via Cloud Relay"
            }
        }
    }

    fun readLocalNowPlaying(): NowPlayingSnapshot {
        val am = audioManager
        val volumePct = if (am != null) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            ((cur * 100f) / max).toInt().coerceIn(0, 100)
        } else {
            -1
        }

        if (!PermissionHelper.isNotificationListenerEnabled(context)) {
            return NowPlayingSnapshot(
                title = if (am?.isMusicActive == true) "Audio playing on device" else "No active media session",
                isPlaying = am?.isMusicActive == true,
                volumePercent = volumePct
            )
        }

        return runCatching {
            val component = ComponentName(context, LinkNotificationListenerService::class.java)
            val controllers = mediaSessionManager?.getActiveSessions(component).orEmpty()
            val primary = controllers.firstOrNull()
            val meta = primary?.metadata
            val playback = primary?.playbackState
            NowPlayingSnapshot(
                title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: if (am?.isMusicActive == true) "Active Media Session" else "No active media session",
                artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                packageName = primary?.packageName.orEmpty(),
                isPlaying = playback?.state == PlaybackState.STATE_PLAYING,
                volumePercent = volumePct
            )
        }.getOrElse {
            NowPlayingSnapshot(volumePercent = volumePct)
        }
    }

    fun clearFeedback() {
        _lastRemoteFeedback.value = null
    }

    private suspend fun broadcastLocalMediaState(targetDeviceId: String) {
        val trusted = trustedDeviceDao.getById(targetDeviceId) ?: return
        if (!trusted.isTrusted) return
        val snap = readLocalNowPlaying()
        remoteRelayEngine.sendSmartControlMessage(
            targetDeviceId,
            "MEDIA_NOW_PLAYING",
            JSONObject().apply {
                put("title", snap.title)
                put("artist", snap.artist)
                put("packageName", snap.packageName)
                put("isPlaying", snap.isPlaying)
                put("volumePercent", snap.volumePercent)
            }
        )
    }

    private fun handleIncomingNowPlaying(peerDeviceId: String, peerName: String, payload: JSONObject) {
        _remoteNowPlaying.value = NowPlayingSnapshot(
            sourceDeviceId = peerDeviceId,
            sourceDeviceName = peerName,
            title = payload.optString("title", "No active media"),
            artist = payload.optString("artist", ""),
            packageName = payload.optString("packageName", ""),
            isPlaying = payload.optBoolean("isPlaying", false),
            volumePercent = payload.optInt("volumePercent", -1)
        )
    }

    private suspend fun handleIncomingMediaCommand(peerDeviceId: String, peerName: String, payload: JSONObject) {
        val trusted = trustedDeviceDao.getById(peerDeviceId)
        if (trusted == null || !trusted.allowRemoteControl) return

        val command = payload.optString("command")
        val am = audioManager ?: return
        when (command) {
            "PLAY_PAUSE" -> dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "NEXT" -> dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_NEXT)
            "PREVIOUS" -> dispatchMediaKey(am, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "VOL_UP" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            "VOL_DOWN" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            "MUTE" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
        }
        broadcastLocalMediaState(peerDeviceId)
        activityLogDao.insert(
            ActivityLogEntity(
                logId = UUID.randomUUID().toString(),
                category = "REMOTE",
                deviceId = peerDeviceId,
                summary = "$peerName executed media control: $command"
            )
        )
    }

    private suspend fun handleIncomingLocateChime(peerDeviceId: String, peerName: String) {
        val trusted = trustedDeviceDao.getById(peerDeviceId)
        if (trusted == null || !trusted.allowRemoteControl) return

        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val ringtone = RingtoneManager.getRingtone(context, uri)
        ringtone?.play()
        delay(2500L)
        ringtone?.stop()
        activityLogDao.insert(
            ActivityLogEntity(
                logId = UUID.randomUUID().toString(),
                category = "REMOTE",
                deviceId = peerDeviceId,
                summary = "$peerName triggered Locate Device chime"
            )
        )
    }

    private fun dispatchMediaKey(am: AudioManager, keyCode: Int) {
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }
}
