package com.karaoke.lyrics.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class LrcParserTest {
    @Test
    fun parsesBasicAndMultiTimestampLines() {
        val lrc = """
            [ti:Song]
            [ar:Artist]
            [00:01.50]First line
            [00:05.123]Second line
            [00:10][01:00.00]Chorus
            not a lyric line
        """.trimIndent()
        val l = LrcParser.parse(lrc)
        assertTrue(l.synced)
        assertEquals(
            listOf(1500L to "First line", 5123L to "Second line", 10000L to "Chorus", 60000L to "Chorus"),
            l.lines.map { it.startMs to it.text },
        )
    }

    @Test
    fun appliesOffsetAndStripsWordTags() {
        val l = LrcParser.parse("[offset:+500]\n[00:02.00]<00:02.00>Hel<00:02.50>lo")
        assertEquals(1500L, l.lines[0].startMs)
        assertEquals("Hello", l.lines[0].text)
    }

    @Test
    fun collapsesRepeatedEmptyLines() {
        val l = LrcParser.parse("[00:01.00]\n[00:02.00]\n[00:03.00]A\n[00:04.00]\n[00:05.00]\n[00:06.00]B")
        assertEquals(listOf("A", "", "B"), l.lines.map { it.text })
    }

    @Test
    fun emptyInputIsNotSynced() {
        assertFalse(LrcParser.parse("").synced)
        assertFalse(LrcParser.parse("just plain text").synced)
    }
}

class TimelineTest {
    private val lyrics = Lyrics(
        listOf(LyricLine(1000, "a"), LyricLine(3000, "b"), LyricLine(20000, "c")),
        synced = true,
    )
    private val tl = KaraokeTimeline(lyrics, durationMs = 30000, maxLineMs = 8000)

    @Test
    fun beforeFirstLine() {
        val f = tl.frameAt(500)
        assertEquals(-1, f.index)
        assertNull(f.current)
        assertEquals("a", f.next)
    }

    @Test
    fun progressWithinLine() {
        val f = tl.frameAt(2000)
        assertEquals(0, f.index)
        assertEquals(0.5f, f.progress, 0.001f)
        assertEquals("b", f.next)
    }

    @Test
    fun longGapIsCappedByMaxLineLength() {
        // строка "b" длится 3000..11000 (а не до 20000)
        assertEquals(0.5f, tl.frameAt(7000).progress, 0.001f)
        assertEquals(1f, tl.frameAt(15000).progress, 0.001f)
    }

    @Test
    fun lastLineUsesDuration() {
        val f = tl.frameAt(24000)
        assertEquals(2, f.index)
        assertEquals(0.5f, f.progress, 0.001f)
        assertNull(f.next)
    }

    @Test
    fun plainLyricsAreSpreadOverDuration() {
        val l = Lyrics.fromPlain("one\n\ntwo\nthree", 100_000)
        assertFalse(l.synced)
        assertEquals(3, l.lines.size)
        assertEquals(8000L, l.lines[0].startMs)
        assertTrue(l.lines.zipWithNext().all { (a, b) -> a.startMs < b.startMs })
        assertTrue(l.lines.last().startMs < 92_000)
    }

    @Test
    fun positionExtrapolation() {
        assertEquals(15_000, estimatePosition(10_000, 1_000, 6_000, 1f, true, 0))
        assertEquals(10_000, estimatePosition(10_000, 1_000, 6_000, 1f, false, 0))
        assertEquals(12_000, estimatePosition(10_000, 1_000, 6_000, 1f, true, 12_000))
        assertEquals(20_000, estimatePosition(10_000, 1_000, 6_000, 2f, true, 0))
    }
}

class TrackQueryTest {
    @Test
    fun cleansTitles() {
        assertEquals("Hey Jude", TrackQueryBuilder.cleanTitle("Hey Jude - Remastered 2015"))
        assertEquals("Song", TrackQueryBuilder.cleanTitle("Song (feat. Someone)"))
        assertEquals("Song", TrackQueryBuilder.cleanTitle("Song [Official Video]"))
        assertEquals("Song", TrackQueryBuilder.cleanTitle("Song ft. Someone"))
        assertEquals("Группа крови", TrackQueryBuilder.cleanTitle("Группа крови (Official Audio)"))
        // обычные скобки, не являющиеся мусором, сохраняются
        assertEquals("Fix You (Part 2)", TrackQueryBuilder.cleanTitle("Fix You (Part 2)"))
    }

    @Test
    fun mainArtist() {
        assertEquals("Queen", TrackQueryBuilder.mainArtist("Queen, David Bowie"))
        assertEquals("Eminem", TrackQueryBuilder.mainArtist("Eminem feat. Rihanna"))
        assertEquals("Florence and the Machine", TrackQueryBuilder.mainArtist("Florence and the Machine"))
        assertEquals("Lil Nas X", TrackQueryBuilder.mainArtist("Lil Nas X"))
        assertEquals("Kino", TrackQueryBuilder.mainArtist("Kino - Topic"))
    }

    @Test
    fun youtubeStyleTitleProducesSplitCandidate() {
        val c = TrackQueryBuilder.candidates("RickAstleyVEVO", "Rick Astley - Never Gonna Give You Up (Official Video)", 213_400)
        assertTrue(c.contains(TrackQuery("Rick Astley", "Never Gonna Give You Up", 213)))
    }

    @Test
    fun noTitleNoCandidates() {
        assertTrue(TrackQueryBuilder.candidates("A", null, null).isEmpty())
    }

    @Test
    fun pickBestPrefersSyncedAndMatchingDuration() {
        val q = TrackQuery("Queen", "Bohemian Rhapsody", 355)
        val best = pickBest(
            listOf(
                LyricsCandidate("Queen", "Bohemian Rhapsody", 355.0, null, "plain"),
                LyricsCandidate("Queen", "Bohemian Rhapsody (Live)", 380.0, "[00:01]x", "plain"),
                LyricsCandidate("Queen", "Bohemian Rhapsody", 356.0, "[00:01]y", "plain"),
                LyricsCandidate("Other", "Other song", 355.0, "[00:01]z", null),
            ),
            q,
        )
        assertEquals("[00:01]y", best?.syncedLyrics)
    }

    @Test
    fun pickBestRejectsInstrumentalAndWrongSongs() {
        val q = TrackQuery("Queen", "Bohemian Rhapsody", 355)
        assertNull(pickBest(listOf(LyricsCandidate("X", "Y", 100.0, null, "t")), q))
        assertNull(pickBest(listOf(LyricsCandidate("Queen", "Bohemian Rhapsody", 355.0, null, null, instrumental = true)), q))
    }
}
