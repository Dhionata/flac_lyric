package comparison

import comparison.application.usecases.ExtractTrackDetailsUseCase
import comparison.domain.models.LrcLibLyrics
import comparison.domain.ports.LrcLibClient
import comparison.infrastructure.clients.CompositeLyricsClientImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExtractTrackDetailsUseCaseTest {

    @Test
    fun `test selectBestCandidate with multiple synced lyrics picks the closest duration`() {
        val cand1 = LrcLibLyrics(id = 1, trackName = "Song A", duration = 240.0, syncedLyrics = "[00:01.00] Line 1")
        val cand2 = LrcLibLyrics(id = 2, trackName = "Song B", duration = 202.0, syncedLyrics = "[00:01.00] Line 2", source = "NetEase")
        val cand3 = LrcLibLyrics(id = 3, trackName = "Song C", duration = 190.0, syncedLyrics = "[00:01.00] Line 3", source = "KuGou")

        val targetDurationSeconds = 200

        val best = ExtractTrackDetailsUseCase.selectBestCandidate(
            listOf(cand1, cand2, cand3),
            targetDurationSeconds
        )

        assertNotNull(best)
        assertEquals(2L, best?.id)
        assertEquals(202.0, best?.duration)
        assertEquals("NetEase", best?.source)
    }

    @Test
    fun `test selectBestCandidate prioritizes synced lyrics over closer unsynced lyrics`() {
        val unsyncedExact = LrcLibLyrics(id = 10, trackName = "Song Unsynced", duration = 200.0, plainLyrics = "Plain text only")
        val syncedClose = LrcLibLyrics(id = 20, trackName = "Song Synced", duration = 205.0, syncedLyrics = "[00:05.00] Synced line")

        val targetDurationSeconds = 200

        val best = ExtractTrackDetailsUseCase.selectBestCandidate(
            listOf(unsyncedExact, syncedClose),
            targetDurationSeconds
        )

        assertNotNull(best)
        assertEquals(20L, best?.id)
        assertEquals("[00:05.00] Synced line", best?.syncedLyrics)
    }

    @Test
    fun `test filterCandidatesWithVerses removes 0-verse credit stubs and preserves valid synced and plain lyrics`() {
        val creditStub = LrcLibLyrics(
            id = 1496180819,
            trackName = "Marceline",
            artistName = "Ana Muller",
            duration = 123.0,
            syncedLyrics = "[00:00.00-1] 作词 : Ana Muller\n[00:00.00-1] 作曲 : Ana Muller",
            plainLyrics = "[00:00.00-1] 作词 : Ana Muller\n[00:00.00-1] 作曲 : Ana Muller",
            source = "NetEase"
        )
        val plainOnly = LrcLibLyrics(
            id = 3409901601,
            trackName = "MARCELINE",
            artistName = "Lil God Dan",
            duration = 113.0,
            plainLyrics = "Plain lyrics without timestamps\nSecond plain verse",
            source = "Genius"
        )
        val validSpotify = LrcLibLyrics(
            id = 999,
            trackName = "Marceline",
            artistName = "Ana Muller",
            duration = 190.0,
            syncedLyrics = "[00:12.00] Primeiro verso real\n[00:16.00] Segundo verso real",
            source = "Spotify"
        )

        val filtered = ExtractTrackDetailsUseCase.filterCandidatesWithVerses(
            listOf(creditStub, plainOnly, validSpotify)
        )

        assertEquals(2, filtered.size)
        assertEquals(3409901601L, filtered[0].id)
        assertNull(filtered[0].syncedLyrics)
        assertNotNull(filtered[0].plainLyrics)
        assertEquals(999L, filtered[1].id)
        assertEquals("Spotify", filtered[1].source)
    }

    @Test
    fun `test selectBestCandidate with no synced lyrics falls back to closest duration plain lyrics`() {
        val unsynced1 = LrcLibLyrics(id = 100, trackName = "Plain 1", duration = 250.0, plainLyrics = "Verso A")
        val unsynced2 = LrcLibLyrics(id = 200, trackName = "Plain 2", duration = 198.0, plainLyrics = "Verso B")

        val targetDurationSeconds = 200

        val best = ExtractTrackDetailsUseCase.selectBestCandidate(
            listOf(unsynced1, unsynced2),
            targetDurationSeconds
        )

        assertNotNull(best)
        assertEquals(200L, best?.id)
    }

    @Test
    fun `test selectBestCandidate with empty candidates returns null`() {
        val best = ExtractTrackDetailsUseCase.selectBestCandidate(emptyList(), 200)
        assertNull(best)
    }

    @Test
    fun `test orderCandidates places selected candidate at index 0 and orders remaining across providers`() {
        val cand1 = LrcLibLyrics(id = 1, trackName = "Far Synced", duration = 260.0, syncedLyrics = "[00:01.00] 1", source = "KuGou")
        val cand2 = LrcLibLyrics(id = 1, trackName = "Closest Synced", duration = 201.0, syncedLyrics = "[00:01.00] 2", source = "Spotify")
        val cand3 = LrcLibLyrics(id = 3, trackName = "Plain", duration = 200.0, plainLyrics = "Verso 3", source = "Genius")

        val targetDuration = 200
        val selected = cand2

        val ordered = ExtractTrackDetailsUseCase.orderCandidates(
            listOf(cand1, cand2, cand3),
            selected,
            targetDuration
        )

        assertEquals(3, ordered.size)
        assertEquals("Spotify", ordered[0].source) // Selected at index 0
        assertEquals("KuGou", ordered[1].source)   // Next synced candidate (kept even with same numeric id=1 from different source)
        assertEquals("Genius", ordered[2].source)  // Unsynced plain candidate at the end
    }

    @Test
    fun `test CompositeLyricsClientImpl queries providers in parallel and preserves source badges`() {
        val providerA = object : LrcLibClient {
            override fun getLyrics(trackName: String, artistName: String, albumName: String?, durationSeconds: Int?): LrcLibLyrics {
                Thread.sleep(250)
                return LrcLibLyrics(id = 10, trackName = trackName, artistName = artistName, duration = 215.0, syncedLyrics = "[00:01.00] A")
            }

            override fun searchLyrics(query: String): List<LrcLibLyrics> {
                Thread.sleep(250)
                return listOf(LrcLibLyrics(id = 10, trackName = query, duration = 215.0, syncedLyrics = "[00:01.00] A"))
            }
        }
        val providerB = object : LrcLibClient {
            override fun getLyrics(trackName: String, artistName: String, albumName: String?, durationSeconds: Int?): LrcLibLyrics {
                Thread.sleep(250)
                return LrcLibLyrics(id = 20, trackName = trackName, artistName = artistName, duration = 200.0, syncedLyrics = "[00:01.00] B", source = "Spotify")
            }

            override fun searchLyrics(query: String): List<LrcLibLyrics> {
                Thread.sleep(250)
                return listOf(LrcLibLyrics(id = 20, trackName = query, duration = 200.0, syncedLyrics = "[00:01.00] B", source = "Spotify"))
            }
        }
        val providerC = object : LrcLibClient {
            override fun getLyrics(trackName: String, artistName: String, albumName: String?, durationSeconds: Int?): LrcLibLyrics {
                Thread.sleep(250)
                return LrcLibLyrics(id = 30, trackName = trackName, artistName = artistName, duration = 198.0, syncedLyrics = "[00:01.00] C", source = "YouTube")
            }

            override fun searchLyrics(query: String): List<LrcLibLyrics> {
                Thread.sleep(250)
                return listOf(LrcLibLyrics(id = 30, trackName = query, duration = 198.0, syncedLyrics = "[00:01.00] C", source = "YouTube"))
            }
        }

        val composite = CompositeLyricsClientImpl(listOf(providerA, providerB, providerC))
        val startMs = System.currentTimeMillis()
        val results = composite.searchLyrics("Linkin Park Numb")
        val elapsedMs = System.currentTimeMillis() - startMs

        assertEquals(3, results.size)
        assertEquals(setOf("LRCLIB", "Spotify", "YouTube"), results.map { it.source }.toSet())
        // 3 providers sleeping 250ms each sequentially would take >= 750ms; parallel takes ~260-500ms
        assertTrue(elapsedMs < 700, "Expected parallel execution (< 700ms), but took ${elapsedMs}ms")

        val best = composite.getLyrics("Numb", "Linkin Park", durationSeconds = 200)
        assertNotNull(best)
        assertEquals("Spotify", best?.source)
        assertEquals(200.0, best?.duration)
    }
}
