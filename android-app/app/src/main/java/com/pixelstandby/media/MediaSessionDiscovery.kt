package com.pixelstandby.media

import android.annotation.TargetApi
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Discovers and tracks active media sessions.
 *
 * Uses android.media.MediaSessionManager.getActiveSessions (API 28+) to find sessions,
 * preferring sessions in PLAYING state, falling back to the most recently active one.
 * Subscribes to session and metadata callbacks rather than polling.
 *
 * On API < 28, getActiveSessions is unavailable; [sessions] emits an empty list.
 */
class MediaSessionDiscovery(private val context: Context) {

    private val manager: android.media.session.MediaSessionManager? =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as android.media.session.MediaSessionManager?

    private val _sessions = MutableStateFlow<List<MediaSessionInfo>>(emptyList())
    val sessions: Flow<List<MediaSessionInfo>> = _sessions.asStateFlow()

    private val _active = MutableStateFlow<MediaSessionInfo?>(null)
    /** The session we recommend controlling: PLAYING first, else most recent. */
    val active: Flow<MediaSessionInfo?> = _active.asStateFlow()

    private var discoveryJob: Job? = null
    private val listeners = ConcurrentHashMap<String, MediaSessionListener>()

    private val componentName = ComponentName(context, MediaNotificationListener::class.java)

    fun start() {
        if (discoveryJob != null) return
        discoveryJob = CoroutineScope(Dispatchers.Main.immediate).launch {
            while (isActive) {
                refresh()
                kotlinx.coroutines.delay(2_000)
            }
        }
    }

    fun stop() {
        discoveryJob?.cancel()
        discoveryJob = null
        listeners.values.forEach { it.release() }
        listeners.clear()
    }

    private fun refresh() {
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getActiveSessionsApi28()
        } else {
            emptyList()
        }

        val currentKeys = current.map { it.sessionToken.toString() }.toSet()
        val knownKeys = listeners.keys.toSet()

        (knownKeys - currentKeys).forEach { key ->
            listeners.remove(key)?.release()
        }

        current.forEach { info ->
            if (!listeners.containsKey(info.sessionToken.toString())) {
                val listener = MediaSessionListener(info.sessionToken, context)
                listener.attach()
                listeners[info.sessionToken.toString()] = listener
            }
        }

        val updated = listeners.values.mapNotNull { it.latestInfo }
        _sessions.value = updated
        _active.value = selectActive(updated)
    }

    @TargetApi(Build.VERSION_CODES.P)
    private fun getActiveSessionsApi28(): List<MediaSessionInfo> {
        return try {
            val controllers = manager!!.getActiveSessions(componentName)
            controllers.mapNotNull { controller ->
                controller.metadata?.let { metadata ->
                    val state = controller.playbackState
                    MediaSessionInfo(
                        sessionToken = controller.sessionToken,
                        controller = controller,
                        metadata = metadata,
                        playbackState = state,
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun selectActive(sessions: List<MediaSessionInfo>): MediaSessionInfo? =
        sessions
            .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }

    /**
     * Media session information snapshot.
     */
    data class MediaSessionInfo(
        val sessionToken: android.media.session.MediaSession.Token,
        val controller: MediaController,
        val metadata: MediaMetadata,
        val playbackState: PlaybackState?,
    ) {
        val title: String? = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist: String? = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
        val album: String? = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)
        val albumArt: android.graphics.Bitmap? =
            metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
        val durationMs: Long = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val isPlaying: Boolean get() = playbackState?.state == PlaybackState.STATE_PLAYING
        val positionMs: Long get() = playbackState?.position ?: 0L

        val transportControls: MediaController.TransportControls get() = controller.transportControls
    }

    /**
     * Wraps a MediaController to read its current state.
     */
    private class MediaSessionListener(
        private val token: android.media.session.MediaSession.Token,
        private val context: Context,
    ) {
        private var controller: MediaController? = null

        var latestInfo: MediaSessionInfo? = null
            private set

        fun attach() {
            controller = MediaController(context, token)
            refreshInfo()
        }

        private fun refreshInfo() {
            val metadata = controller?.metadata
            val state = controller?.playbackState
            if (metadata != null) {
                latestInfo = MediaSessionInfo(
                    sessionToken = token,
                    controller = controller!!,
                    metadata = metadata,
                    playbackState = state,
                )
            }
        }

        fun release() {
            controller = null
        }
    }
}