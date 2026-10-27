package com.karaoke.lyrics

import android.util.LruCache
import com.karaoke.lyrics.core.KaraokeTimeline
import com.karaoke.lyrics.core.Lyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data class Ready(val lyrics: Lyrics, val timeline: KaraokeTimeline) : LyricsState
    data object NotFound : LyricsState
    data class Failed(val message: String) : LyricsState
}

/** Следит за сменой трека и подгружает для него текст. */
object LyricsController {
    private val _state = MutableStateFlow<LyricsState>(LyricsState.Idle)
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    private val cache = LruCache<String, Lyrics>(64)
    private val retryRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var started = false

    fun retry() { retryRequests.tryEmit(Unit) }

    fun start(scope: CoroutineScope) {
        if (started) return
        started = true
        scope.launch {
            val trackKeys = MediaTracker.track
                .map { t -> t?.let { "${it.artist.orEmpty()}\u0000${it.title}" } }
                .distinctUntilChanged()
            combine(trackKeys, retryRequests.onStart { emit(Unit) }) { key, _ -> key }
                .collectLatest { key -> load(key) }
        }
    }

    private suspend fun load(key: String?) {
        if (key == null) {
            _state.value = LyricsState.Idle
            return
        }
        cache.get(key)?.let { cached ->
            _state.value = ready(cached)
            return
        }
        _state.value = LyricsState.Loading
        // Плееры часто присылают метаданные по частям (сначала без длительности).
        delay(500)

        var attempt = 0
        while (true) {
            val track = MediaTracker.track.value ?: return
            try {
                val lyrics = withContext(Dispatchers.IO) {
                    LrcLibClient.find(track.artist, track.title, track.album, track.durationMs)
                }
                if (lyrics == null) {
                    _state.value = LyricsState.NotFound
                } else {
                    cache.put(key, lyrics)
                    _state.value = ready(lyrics)
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _state.value = LyricsState.Failed("Нет связи с сервером текстов")
                if (++attempt >= 4) return
                delay(5_000L * attempt)
            } catch (e: Exception) {
                _state.value = LyricsState.Failed(e.message ?: "Ошибка загрузки")
                return
            }
        }
    }

    private fun ready(lyrics: Lyrics): LyricsState.Ready {
        val duration = MediaTracker.track.value?.durationMs ?: 0
        return LyricsState.Ready(lyrics, KaraokeTimeline(lyrics, duration))
    }
}
