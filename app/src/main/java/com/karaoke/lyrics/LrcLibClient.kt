package com.karaoke.lyrics

import com.karaoke.lyrics.core.LrcParser
import com.karaoke.lyrics.core.Lyrics
import com.karaoke.lyrics.core.LyricsCandidate
import com.karaoke.lyrics.core.TrackQuery
import com.karaoke.lyrics.core.TrackQueryBuilder
import com.karaoke.lyrics.core.pickBest
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Клиент LRCLIB (https://lrclib.net) — бесплатной открытой базы текстов песен
 * с таймкодами (формат LRC). Ключ API не нужен.
 */
object LrcLibClient {
    private const val BASE = "https://lrclib.net/api"
    private const val USER_AGENT = "KaraokeLyrics/1.0 (Android)"

    /**
     * Ищет текст для трека. Возвращает null, если текста нет;
     * бросает IOException, если сеть недоступна (тогда стоит повторить позже).
     */
    fun find(artist: String?, title: String, album: String?, durationMs: Long): Lyrics? {
        val queries = TrackQueryBuilder.candidates(artist, title, durationMs)
        var plainFallback: String? = null
        var succeeded = false
        var lastError: IOException? = null

        fun attempt(block: () -> LyricsCandidate?): LyricsCandidate? = try {
            block().also { succeeded = true }
        } catch (e: IOException) {
            lastError = e
            null
        }

        for (q in queries) {
            // 1) Точное совпадение — самый быстрый и надёжный вариант.
            if (!album.isNullOrBlank() && q.durationSec != null) {
                val exact = attempt { getExact(q, album) }
                if (exact != null && !exact.instrumental) {
                    parseSynced(exact.syncedLyrics)?.let { return it }
                    if (plainFallback == null) plainFallback = exact.plainLyrics
                }
            }
            // 2) Поиск и выбор лучшего результата.
            val best = attempt { pickBest(search(q), q) }
            if (best != null) {
                parseSynced(best.syncedLyrics)?.let { return it }
                if (plainFallback == null) plainFallback = best.plainLyrics
            }
        }

        if (!succeeded && lastError != null) throw lastError!!
        return plainFallback
            ?.takeIf { durationMs > 0 }
            ?.let { Lyrics.fromPlain(it, durationMs) }
            ?.takeUnless { it.isEmpty }
    }

    private fun parseSynced(lrc: String?): Lyrics? =
        lrc?.takeIf { it.isNotBlank() }?.let(LrcParser::parse)?.takeIf { it.synced }

    private fun getExact(q: TrackQuery, album: String): LyricsCandidate? {
        val url = "$BASE/get?artist_name=${enc(q.artist)}&track_name=${enc(q.title)}" +
            "&album_name=${enc(album)}&duration=${q.durationSec}"
        return httpGet(url)?.let { JSONObject(it).toCandidate() }
    }

    private fun search(q: TrackQuery): List<LyricsCandidate> {
        val url = "$BASE/search?artist_name=${enc(q.artist)}&track_name=${enc(q.title)}"
        val body = httpGet(url) ?: return emptyList()
        val arr = JSONArray(body)
        return (0 until arr.length()).map { arr.getJSONObject(it).toCandidate() }
    }

    private fun JSONObject.toCandidate() = LyricsCandidate(
        artist = optString("artistName"),
        title = optString("trackName"),
        durationSec = if (isNull("duration")) null else optDouble("duration"),
        syncedLyrics = nullableString("syncedLyrics"),
        plainLyrics = nullableString("plainLyrics"),
        instrumental = optBoolean("instrumental", false),
    )

    // optString() превращает JSON null в строку "null", поэтому проверяем отдельно.
    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** GET-запрос; null при 404 («не найдено»). */
    private fun httpGet(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            return when (val code = conn.responseCode) {
                200 -> conn.inputStream.bufferedReader().use { it.readText() }
                404 -> null
                else -> throw IOException("HTTP $code")
            }
        } catch (e: org.json.JSONException) {
            throw IOException(e)
        } finally {
            conn.disconnect()
        }
    }
}
