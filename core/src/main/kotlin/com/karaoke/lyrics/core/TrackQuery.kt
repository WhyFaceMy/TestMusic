package com.karaoke.lyrics.core

/** Запрос в базу текстов: исполнитель + название (+ длительность для точного совпадения). */
data class TrackQuery(val artist: String, val title: String, val durationSec: Int?)

/**
 * Плееры отдают «грязные» метаданные: "Song (feat. X) - Remastered 2011",
 * YouTube — "Artist - Song (Official Video)" с исполнителем "ArtistVEVO".
 * Здесь из них строятся несколько вариантов запроса, от точного к упрощённому.
 */
object TrackQueryBuilder {
    private val bracketJunk = Regex(
        """\s*[(\[][^)\]]*(official|video|audio|lyric|remaster|live|version|edit|mix|feat\.?|ft\.|prod\.|hd|hq|4k|clip|клип|премьера)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val dashJunk = Regex(
        """\s+-\s+(.*remaster.*|.*version.*|.*edit.*|live.*|.*mix.*|mono|stereo)$""",
        RegexOption.IGNORE_CASE,
    )
    private val featJunk = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val artistSplit = Regex("""\s*(,|&|;|/|\bfeat\.?|\bft\.?|\bfeaturing\b)\s*""", RegexOption.IGNORE_CASE)
    private val channelJunk = Regex("""(\s*-\s*topic|vevo|\s+official)$""", RegexOption.IGNORE_CASE)

    fun cleanTitle(title: String): String =
        title.replace(bracketJunk, "")
            .replace(dashJunk, "")
            .replace(featJunk, "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun mainArtist(artist: String): String =
        artist.replace(channelJunk, "").split(artistSplit).first().trim()

    fun candidates(rawArtist: String?, rawTitle: String?, durationMs: Long?): List<TrackQuery> {
        val title = rawTitle?.trim().orEmpty()
        val artist = rawArtist?.trim().orEmpty()
        if (title.isEmpty()) return emptyList()
        val dur = durationMs?.takeIf { it > 0 }?.let { ((it + 500) / 1000).toInt() }

        val out = LinkedHashSet<TrackQuery>()
        if (artist.isNotEmpty()) {
            out += TrackQuery(artist, title, dur)
            out += TrackQuery(mainArtist(artist), cleanTitle(title), dur)
        }
        // Видео-плееры: название вида "Исполнитель - Песня".
        val dash = title.indexOf(" - ")
        if (dash > 0) {
            val a = title.substring(0, dash)
            val t = title.substring(dash + 3)
            out += TrackQuery(mainArtist(a), cleanTitle(t), dur)
        }
        return out.filter { it.title.isNotBlank() && it.artist.isNotBlank() }
    }
}

/** Кандидат из результатов поиска. */
data class LyricsCandidate(
    val artist: String,
    val title: String,
    val durationSec: Double?,
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val instrumental: Boolean = false,
)

/** Выбирает лучший результат: синхронизированный текст и близкая длительность важнее всего. */
fun pickBest(results: List<LyricsCandidate>, query: TrackQuery): LyricsCandidate? {
    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    val wantTitle = norm(TrackQueryBuilder.cleanTitle(query.title))
    val wantArtist = norm(TrackQueryBuilder.mainArtist(query.artist))

    return results
        .filter { !it.instrumental && (!it.syncedLyrics.isNullOrBlank() || !it.plainLyrics.isNullOrBlank()) }
        .map { c ->
            var score = 0.0
            if (!c.syncedLyrics.isNullOrBlank()) score += 50
            val t = norm(TrackQueryBuilder.cleanTitle(c.title))
            if (t == wantTitle) score += 30 else if (t.contains(wantTitle) || wantTitle.contains(t)) score += 10
            val a = norm(c.artist)
            if (a.contains(wantArtist) || wantArtist.contains(a)) score += 20
            if (query.durationSec != null && c.durationSec != null) {
                val diff = kotlin.math.abs(c.durationSec - query.durationSec)
                score += when {
                    diff <= 2 -> 25.0
                    diff <= 5 -> 10.0
                    diff > 20 -> -40.0
                    else -> 0.0
                }
            }
            c to score
        }
        .filter { it.second >= 40 }
        .maxByOrNull { it.second }
        ?.first
}
