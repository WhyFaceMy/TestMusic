package com.karaoke.lyrics.core

/** Что показывать в конкретный момент времени. */
data class KaraokeFrame(
    /** Индекс текущей строки или -1, если первая строка ещё не началась. */
    val index: Int,
    /** Доля «пропетой» текущей строки, 0..1 — по ней закрашивается текст. */
    val progress: Float,
    val previous: String?,
    val current: String?,
    val next: String?,
)

/**
 * Определяет текущую строку и прогресс её «пропевания» по позиции трека.
 * Конец строки = начало следующей, но не дольше [maxLineMs]
 * (чтобы во время длинного проигрыша строка не закрашивалась 30 секунд).
 */
class KaraokeTimeline(
    private val lyrics: Lyrics,
    private val durationMs: Long = 0,
    private val maxLineMs: Long = 8_000,
) {
    private val lines = lyrics.lines

    fun frameAt(positionMs: Long): KaraokeFrame {
        if (lines.isEmpty()) return KaraokeFrame(-1, 0f, null, null, null)
        val i = indexAt(positionMs)
        if (i < 0) return KaraokeFrame(-1, 0f, null, null, lines[0].text)

        val line = lines[i]
        val nextStart = lines.getOrNull(i + 1)?.startMs
            ?: if (durationMs > line.startMs) durationMs else line.startMs + maxLineMs
        val end = minOf(nextStart, line.startMs + maxLineMs)
        val length = (end - line.startMs).coerceAtLeast(1)
        val progress = ((positionMs - line.startMs).toFloat() / length).coerceIn(0f, 1f)

        return KaraokeFrame(
            index = i,
            progress = progress,
            previous = lines.getOrNull(i - 1)?.text,
            current = line.text,
            next = lines.getOrNull(i + 1)?.text,
        )
    }

    /** Бинарный поиск последней строки, начавшейся не позже [positionMs]. */
    fun indexAt(positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= positionMs) {
                ans = mid; lo = mid + 1
            } else hi = mid - 1
        }
        return ans
    }
}

/**
 * Текущая позиция трека по данным системного плеера (MediaSession PlaybackState):
 * плеер сообщает позицию редко, поэтому между обновлениями её надо экстраполировать.
 */
fun estimatePosition(
    reportedPositionMs: Long,
    reportedAtElapsedMs: Long,
    nowElapsedMs: Long,
    speed: Float,
    playing: Boolean,
    durationMs: Long,
): Long {
    if (!playing || reportedAtElapsedMs <= 0) return reportedPositionMs.coerceAtLeast(0)
    val pos = reportedPositionMs + ((nowElapsedMs - reportedAtElapsedMs) * speed).toLong()
    return if (durationMs > 0) pos.coerceIn(0, durationMs) else pos.coerceAtLeast(0)
}
