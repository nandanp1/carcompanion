package com.carcompanion.app.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.carcompanion.app.model.AppStore
import com.carcompanion.app.model.MusicState
import com.carcompanion.app.navigation.MapsNotificationListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SpotifyMediaMonitor(private val context: Context, private val scope: CoroutineScope,
                          private val jpegQuality: () -> Int) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MapsNotificationListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var registered = false
    private var artworkJob: Job? = null
    private var artworkKey: String? = null
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publishMetadata(metadata)
        override fun onPlaybackStateChanged(state: PlaybackState?) = publishPlayback(state)
        override fun onSessionDestroyed() { bind(null); refresh() }
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions -> choose(sessions.orEmpty()) }

    fun start() = refresh()
    fun refresh() {
        try {
            if (!registered) {
                manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
                registered = true
            }
            choose(manager.getActiveSessions(component))
        } catch (_: SecurityException) {
            if (registered) runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
            registered = false
            bind(null)
            AppStore.diagnostic { it.copy(mediaStatus = "Notification access is required for Spotify MediaSession") }
        }
    }

    private fun choose(sessions: List<MediaController>) {
        val spotify = sessions.filter { it.packageName == "com.spotify.music" }
        val chosen = spotify.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: spotify.firstOrNull()
        bind(chosen)
    }

    private fun bind(next: MediaController?) {
        if (controller?.sessionToken == next?.sessionToken && next != null) return
        controller?.unregisterCallback(callback)
        controller = next
        artworkJob?.cancel()
        artworkKey = null
        AppStore.artwork(null)
        if (next == null) {
            AppStore.update { it.copy(music = MusicState()) }
            AppStore.diagnostic { it.copy(mediaStatus = "No active Spotify session") }
        } else {
            next.registerCallback(callback, handler)
            publishMetadata(next.metadata)
        }
    }

    private fun publishMetadata(metadata: MediaMetadata?) {
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val identity = MediaIdentity.trackId(metadata?.getString(MediaMetadata.METADATA_KEY_MEDIA_ID), title, artist, album)
        val old = AppStore.state.value.music
        val duration = (metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceIn(0, 86_400_000L)
        val playback = controller?.playbackState
        val playing = playback?.state == PlaybackState.STATE_PLAYING
        val position = playback?.let { MediaIdentity.position(
            it.position.coerceAtLeast(0), duration, playing, it.playbackSpeed,
            it.lastPositionUpdateTime, SystemClock.elapsedRealtime(),
        ) } ?: 0L
        // Publish one complete snapshot directly from the callback, before reading or encoding art.
        AppStore.update { it.copy(music = MusicState(title, artist, album, duration, position, playing,
            artworkId = if (old.trackId == identity) old.artworkId else null, trackId = identity)) }
        if (old.trackId != identity) {
            AppStore.artwork(null)
            Log.d("CarCompanionMedia", "Metadata callback: track=${identity.take(8)}")
            AppStore.log("Spotify track changed · preparing artwork")
        }
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val uri = metadata?.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val key = "$identity:${bitmap?.generationId}:${uri}:${jpegQuality()}"
        if (key == artworkKey) return
        artworkKey = key
        artworkJob?.cancel()
        artworkJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            val art = ArtworkEncoder.encode(context, bitmap, uri, jpegQuality())
            if (AppStore.state.value.music.trackId != identity || artworkKey != key) return@launch
            AppStore.update { it.copy(music = it.music.copy(artworkId = art?.contentId)) }
            AppStore.artwork(art)
            Log.d("CarCompanionMedia", "Artwork prepared: bytes=${art?.jpeg?.size ?: 0} elapsedMs=${SystemClock.elapsedRealtime() - startedAt}")
            AppStore.diagnostic { it.copy(mediaStatus = if (art == null) "Spotify connected · artwork unavailable" else "Spotify connected · ${art.jpeg.size / 1024} KiB artwork") }
        }
    }

    fun tick() {
        val state = controller?.playbackState ?: return
        publishPlayback(state)
    }

    private fun publishPlayback(state: PlaybackState?) {
        val playing = state?.state == PlaybackState.STATE_PLAYING
        AppStore.update {
            val current = it.music
            it.copy(music = current.copy(playing = playing, positionMs = if (state == null) current.positionMs else MediaIdentity.position(
                state.position.coerceAtLeast(0), current.durationMs, playing, state.playbackSpeed,
                state.lastPositionUpdateTime, SystemClock.elapsedRealtime(),
            )))
        }
    }

    fun command(command: Int) {
        val target = controller ?: return
        val state = target.playbackState ?: return
        val actions = state.actions
        try {
            when (command) {
                1 -> if (state.state == PlaybackState.STATE_PLAYING) {
                    if (actions and (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE) != 0L) target.transportControls.pause()
                    else AppStore.log("Spotify session does not support pause")
                } else if (actions and (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE) != 0L) target.transportControls.play()
                else AppStore.log("Spotify session does not support play")
                2 -> if (actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L) target.transportControls.skipToNext()
                else AppStore.log("Spotify session does not support next track")
                3 -> if (actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L) target.transportControls.skipToPrevious()
                else AppStore.log("Spotify session does not support previous track")
            }
        } catch (_: SecurityException) { AppStore.log("Spotify rejected a playback command") }
    }

    fun settingsChanged() { controller?.metadata?.let(::publishMetadata) }
    fun stop() {
        if (registered) manager.removeOnActiveSessionsChangedListener(sessionsListener)
        registered = false
        controller?.unregisterCallback(callback)
        controller = null
        artworkJob?.cancel()
    }
}
