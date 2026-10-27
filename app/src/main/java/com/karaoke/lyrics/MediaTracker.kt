package com.karaoke.lyrics

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.karaoke.lyrics.core.estimatePosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Трек, который сейчас играет в каком-либо плеере на телефоне. */
data class NowPlaying(
    val packageName: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
) {
    val displayName: String get() = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title).joinToString(" — ")
}

/** Состояние воспроизведения в том виде, в каком его сообщил плеер. */
data class PlaybackInfo(
    val positionMs: Long,
    val updatedAtElapsedMs: Long,
    val speed: Float,
    val playing: Boolean,
) {
    /** Позиция «прямо сейчас» — плеер обновляет её редко, поэтому досчитываем сами. */
    fun positionNow(durationMs: Long): Long = estimatePosition(
        positionMs, updatedAtElapsedMs, SystemClock.elapsedRealtime(), speed, playing, durationMs,
    )

    companion object {
        val STOPPED = PlaybackInfo(0, 0, 1f, false)
    }
}

/**
 * Следит за медиа-сессиями всех плееров (Spotify, YouTube Music, Яндекс Музыка, VK и т.д.).
 * Android отдаёт этот список только приложению с доступом к уведомлениям,
 * поэтому запускается из [MediaListenerService].
 */
object MediaTracker {
    private val _track = MutableStateFlow<NowPlaying?>(null)
    val track: StateFlow<NowPlaying?> = _track.asStateFlow()

    private val _playback = MutableStateFlow(PlaybackInfo.STOPPED)
    val playback: StateFlow<PlaybackInfo> = _playback.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private val watched = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var ordered: List<MediaController> = emptyList()
    private var active: MediaController? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { list -> updateSessions(list.orEmpty()) }

    fun start(context: Context, listener: ComponentName) {
        if (manager != null) return
        val m = context.getSystemService(MediaSessionManager::class.java) ?: return
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, listener, main)
            manager = m
            updateSessions(m.getActiveSessions(listener))
        } catch (e: SecurityException) {
            // Доступ к уведомлениям ещё не выдан.
        }
    }

    fun stop() {
        manager?.removeOnActiveSessionsChangedListener(sessionsListener)
        manager = null
        watched.values.forEach { (c, cb) -> c.unregisterCallback(cb) }
        watched.clear()
        ordered = emptyList()
        active = null
        _track.value = null
        _playback.value = PlaybackInfo.STOPPED
    }

    fun playPause() {
        val c = active ?: return
        if (c.isPlaying()) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() { active?.transportControls?.skipToNext() }

    fun previous() { active?.transportControls?.skipToPrevious() }

    private fun updateSessions(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        watched.keys.filter { it !in tokens }.forEach { token ->
            watched.remove(token)?.let { (c, cb) -> c.unregisterCallback(cb) }
        }
        for (c in list) {
            if (c.sessionToken in watched) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = select()
                override fun onMetadataChanged(metadata: MediaMetadata?) = select()
                override fun onSessionDestroyed() = select()
            }
            c.registerCallback(cb, main)
            watched[c.sessionToken] = c to cb
        }
        // Система отдаёт сессии в порядке приоритета.
        ordered = list.map { watched[it.sessionToken]!!.first }
        select()
    }

    /** Выбираем плеер, который реально играет; при паузе остаёмся на текущем. */
    private fun select() {
        val current = active?.let { a -> ordered.firstOrNull { it.sessionToken == a.sessionToken } }
        val chosen = when {
            current != null && current.isPlaying() -> current
            else -> ordered.firstOrNull { it.isPlaying() } ?: current ?: ordered.firstOrNull()
        }
        active = chosen
        publish(chosen)
    }

    private fun MediaController.isPlaying() = playbackState?.state == PlaybackState.STATE_PLAYING

    private fun publish(c: MediaController?) {
        val md = c?.metadata
        val title = md?.text(MediaMetadata.METADATA_KEY_TITLE)
            ?: md?.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        _track.value = if (c == null || md == null || title == null) null else NowPlaying(
            packageName = c.packageName,
            title = title,
            artist = md.text(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md.text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
            album = md.text(MediaMetadata.METADATA_KEY_ALBUM),
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0),
        )

        val ps = c?.playbackState
        _playback.value = if (ps == null) PlaybackInfo.STOPPED else PlaybackInfo(
            positionMs = ps.position,
            updatedAtElapsedMs = ps.lastPositionUpdateTime,
            speed = ps.playbackSpeed.takeIf { it > 0f } ?: 1f,
            playing = ps.state == PlaybackState.STATE_PLAYING,
        )
    }

    private fun MediaMetadata.text(key: String): String? =
        getString(key)?.trim()?.takeIf { it.isNotEmpty() }
}
