package comparison

import comparison.application.usecases.ExtractTrackDetailsUseCase
import comparison.domain.models.LrcLibLyrics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ExtractTrackDetailsUseCaseTest {

    @Test
    fun `test selectBestCandidate with multiple synced lyrics picks the closest duration`() {
        val cand1 = LrcLibLyrics(id = 1, trackName = "Song A", duration = 240.0, syncedLyrics = "[00:01.00] Line 1")
        val cand2 = LrcLibLyrics(id = 2, trackName = "Song B", duration = 202.0, syncedLyrics = "[00:01.00] Line 2")
        val cand3 = LrcLibLyrics(id = 3, trackName = "Song C", duration = 190.0, syncedLyrics = "[00:01.00] Line 3")

        val targetDurationSeconds = 200

        val best = ExtractTrackDetailsUseCase.selectBestCandidate(
            listOf(cand1, cand2, cand3),
            targetDurationSeconds
        )

        assertNotNull(best)
        assertEquals(2L, best?.id)
        assertEquals(202.0, best?.duration)
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
    fun `test selectBestCandidate with no synced lyrics falls back to closest duration`() {
        val unsynced1 = LrcLibLyrics(id = 100, trackName = "Plain 1", duration = 250.0, plainLyrics = "A")
        val unsynced2 = LrcLibLyrics(id = 200, trackName = "Plain 2", duration = 198.0, plainLyrics = "B")

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
    fun `test orderCandidates places selected candidate at index 0 and orders remaining`() {
        val cand1 = LrcLibLyrics(id = 1, trackName = "Far Synced", duration = 260.0, syncedLyrics = "[00:01.00] 1")
        val cand2 = LrcLibLyrics(id = 2, trackName = "Closest Synced", duration = 201.0, syncedLyrics = "[00:01.00] 2")
        val cand3 = LrcLibLyrics(id = 3, trackName = "Plain", duration = 200.0, plainLyrics = "3")

        val targetDuration = 200
        val selected = cand2

        val ordered = ExtractTrackDetailsUseCase.orderCandidates(
            listOf(cand1, cand2, cand3),
            selected,
            targetDuration
        )

        assertEquals(3, ordered.size)
        assertEquals(2L, ordered[0].id) // Selected at index 0
        assertEquals(1L, ordered[1].id) // Next synced candidate
        assertEquals(3L, ordered[2].id) // Unsynced candidate at the end
    }
}
