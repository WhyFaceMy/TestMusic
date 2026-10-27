package com.karaoke.lyrics.core

/** Одна строка текста песни, начинающаяся в момент [startMs]. */
data class LyricLine(val startMs: Long, val text: String)

/**
 * Текст песни, готовый к показу.
 * [synced] = false означает, что время строк рассчитано примерно
 * (текст был без таймкодов и строки равномерно распределены по длительности трека).
 */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {
    val isEmpty: Boolean get() = lines.isEmpty()

    companion object {
        val EMPTY = Lyrics(emptyList(), synced = false)

        /**
         * Делает «примерную» синхронизацию для текста без таймкодов:
         * строки распределяются по длительности трека пропорционально их длине.
         * Первые и последние 8% трека считаются вступлением и концовкой.
         */
        fun fromPlain(text: String, durationMs: Long): Lyrics {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty() || durationMs <= 0) return EMPTY
            val intro = (durationMs * 0.08).toLong()
            val usable = durationMs - 2 * intro
            val weights = lines.map { it.length.coerceAtLeast(8).toDouble() }
            val total = weights.sum()
            var t = intro.toDouble()
            val result = lines.mapIndexed { i, line ->
                val start = t.toLong()
                t += usable * weights[i] / total
                LyricLine(start, line)
            }
            return Lyrics(result, synced = false)
        }
    }
}
