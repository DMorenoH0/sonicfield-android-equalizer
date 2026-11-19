package com.sonicfield.audio

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf

data class NowPlayingTrack(
    val title: String,
    val artist: String?,
    val album: String?,
    val sourceApp: String?,
    val isPlaying: Boolean,
)

object NowPlayingState {
    val track = mutableStateOf<NowPlayingTrack?>(null)
    val listenerEnabled = mutableStateOf(false)
}

class NowPlayingListenerService : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var sessions: MediaSessionManager
    private val controllers = mutableMapOf<MediaController, MediaController.Callback>()

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { active ->
        syncControllers(active.orEmpty())
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NowPlayingState.listenerEnabled.value = true
        sessions = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
        try {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, componentName)
            syncControllers(sessions.getActiveSessions(componentName))
        } catch (_: SecurityException) {
            NowPlayingState.listenerEnabled.value = false
            NowPlayingState.track.value = null
        }
    }

    override fun onListenerDisconnected() {
        removeSessionListener()
        NowPlayingState.listenerEnabled.value = false
        NowPlayingState.track.value = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        removeSessionListener()
        NowPlayingState.listenerEnabled.value = false
        NowPlayingState.track.value = null
        super.onDestroy()
    }

    private val componentName: ComponentName
        get() = ComponentName(this, NowPlayingListenerService::class.java)

    private fun refreshSessions() {
        if (!::sessions.isInitialized) return
        mainHandler.post {
            try {
                syncControllers(sessions.getActiveSessions(componentName))
            } catch (_: SecurityException) {
                NowPlayingState.track.value = null
            }
        }
    }

    private fun removeSessionListener() {
        if (::sessions.isInitialized) {
            try { sessions.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) { }
        }
        controllers.forEach { (controller, callback) ->
            try { controller.unregisterCallback(callback) } catch (_: Exception) { }
        }
        controllers.clear()
    }

    private fun syncControllers(active: List<MediaController>) {
        val tokens = active.map { it.sessionToken }.toSet()
        controllers.keys.filter { it.sessionToken !in tokens }.forEach { old ->
            controllers.remove(old)?.let { callback -> try { old.unregisterCallback(callback) } catch (_: Exception) { } }
        }
        active.forEach { controller ->
            if (controllers.keys.none { it.sessionToken == controller.sessionToken }) {
                val callback = object : MediaController.Callback() {
                    override fun onMetadataChanged(metadata: MediaMetadata?) = publishCurrent()
                    override fun onPlaybackStateChanged(state: PlaybackState?) = publishCurrent()
                    override fun onSessionDestroyed() = refreshSessions()
                }
                controllers[controller] = callback
                try { controller.registerCallback(callback, mainHandler) } catch (_: Exception) { }
            }
        }
        publishCurrent(active)
    }

    private fun publishCurrent(active: List<MediaController> = controllers.keys.toList()) {
        val ordered = active.sortedByDescending { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val selected = ordered.firstOrNull { it.metadata?.usefulTitle() != null }
        val metadata = selected?.metadata
        val title = metadata?.usefulTitle()
        if (selected == null || title == null) {
            NowPlayingState.track.value = null
            return
        }
        val source = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(selected.packageName, 0)).toString()
        } catch (_: Exception) { selected.packageName }
        NowPlayingState.track.value = NowPlayingTrack(
            title = title,
            artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            sourceApp = source,
            isPlaying = selected.playbackState?.state == PlaybackState.STATE_PLAYING,
        )
    }

    private fun MediaMetadata.usefulTitle(): String? =
        getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf(String::isNotBlank)
            ?: getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)?.takeIf(String::isNotBlank)
            ?: description?.title?.toString()?.takeIf(String::isNotBlank)
}
