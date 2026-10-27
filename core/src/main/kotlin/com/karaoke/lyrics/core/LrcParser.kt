package com.karaoke.lyrics.core

/**
 * Разбор формата LRC:
 *   [00:12.34]Текст строки
 *   [00:12.34][01:40.00]Повторяющийся припев
 *   [offset:+250]  — сдвиг в миллисекундах (положительный = текст раньше)
 * Пословные метки расширенного LRC (<00:12.50>) вырезаются.
 */
object LrcParser {
    private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val offsetTag = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val wordTag = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(lrc: String): Lyrics {
        val offset = offsetTag.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lines = mutableListOf<LyricLine>()

        for (raw in lrc.lineSequence()) {
            val line = raw.trim()
            var pos = 0
            val times = mutableListOf<Long>()
            while (true) {
                val m = timeTag.matchAt(line, pos) ?: break
                times += toMillis(m)
                pos = m.range.last + 1
            }
            if (times.isEmpty()) continue
            val text = line.substring(pos).replace(wordTag, "").trim()
            for (t in times) lines += LyricLine((t - offset).coerceAtLeast(0), text)
        }

        lines.sortBy { it.startMs }
        // Пустые строки нужны как «паузы» между куплетами, но несколько подряд не нужны.
        val compact = lines.filterIndexed { i, l ->
            l.text.isNotEmpty() || (i > 0 && lines[i - 1].text.isNotEmpty())
        }
        return Lyrics(compact, synced = compact.any { it.text.isNotEmpty() })
    }

    private fun toMillis(m: MatchResult): Long {
        val min = m.groupValues[1].toLong()
        val sec = m.groupValues[2].toLong()
        val frac = m.groupValues[3]
        val ms = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.toLong()
        }
        return (min * 60 + sec) * 1000 + ms
    }
}
