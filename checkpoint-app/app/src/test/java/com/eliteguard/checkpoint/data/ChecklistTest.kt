package com.eliteguard.checkpoint.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The officer-facing rules: a scanned checkpoint turns green and drops to the bottom, and a tag
 * is matched to a checkpoint by the name written on it.
 */
class ChecklistTest {

    private val tour = listOf(
        LogCheckpoint("log", "c1", "Front Gate", 0),
        LogCheckpoint("log", "c2", "Lobby", 1),
        LogCheckpoint("log", "c3", "Pool Deck", 2),
        LogCheckpoint("log", "c4", "Roof", 3),
    )

    private fun names(rows: List<ChecklistRow>) = rows.map { it.name }

    @Test fun nothingScannedKeepsTheAdministratorsOrder() {
        assertEquals(listOf("Front Gate", "Lobby", "Pool Deck", "Roof"), names(buildChecklist(tour, emptyMap())))
    }

    @Test fun aScannedCheckpointDropsToTheBottom() {
        val rows = buildChecklist(tour, mapOf("c1" to "2026-09-26T01:00:00Z"))
        assertEquals(listOf("Lobby", "Pool Deck", "Roof", "Front Gate"), names(rows))
        assertEquals(true, rows.last().isScanned)
        assertEquals(3, rows.count { !it.isScanned })
    }

    /** Scanned out of order: the bottom section reflects the walking order, not the admin's. */
    @Test fun scannedCheckpointsStackInTheOrderTheyWereScanned() {
        val rows = buildChecklist(
            tour,
            mapOf(
                "c3" to "2026-09-26T01:05:00Z",
                "c1" to "2026-09-26T01:00:00Z",
                "c4" to "2026-09-26T01:10:00Z",
            ),
        )
        assertEquals(listOf("Lobby", "Front Gate", "Pool Deck", "Roof"), names(rows))
    }

    @Test fun everythingScannedLeavesThemInWalkingOrder() {
        val rows = buildChecklist(
            tour,
            mapOf(
                "c4" to "2026-09-26T01:01:00Z",
                "c3" to "2026-09-26T01:02:00Z",
                "c2" to "2026-09-26T01:03:00Z",
                "c1" to "2026-09-26T01:04:00Z",
            ),
        )
        assertEquals(listOf("Roof", "Pool Deck", "Lobby", "Front Gate"), names(rows))
        assertEquals(0, rows.count { !it.isScanned })
    }

    @Test fun theScanTimeIsCarriedOntoTheRow() {
        val rows = buildChecklist(tour, mapOf("c2" to "2026-09-26T01:00:00Z"))
        assertEquals("2026-09-26T01:00:00Z", rows.last().scannedAt)
        assertNull(rows.first().scannedAt)
    }

    @Test fun anEmptyTourProducesAnEmptyList() {
        assertEquals(emptyList<ChecklistRow>(), buildChecklist(emptyList(), emptyMap()))
    }

    // ---------------------------------------------------------------- matching

    @Test fun anExactNameMatches() {
        assertEquals("c2", matchByName(tour, "Lobby")?.checkpointId)
    }

    /** A tag written on one phone and read on another must not miss over casing or spacing. */
    @Test fun matchingIgnoresCaseAndStraySpacing() {
        assertEquals("c1", matchByName(tour, "front gate")?.checkpointId)
        assertEquals("c1", matchByName(tour, "  FRONT   GATE  ")?.checkpointId)
        assertEquals("c3", matchByName(tour, "Pool\tDeck")?.checkpointId)
    }

    @Test fun aNameNotOnThisTourMatchesNothing() {
        assertNull(matchByName(tour, "Basement"))
    }

    @Test fun aBlankOrMissingNameMatchesNothing() {
        assertNull(matchByName(tour, null))
        assertNull(matchByName(tour, ""))
        assertNull(matchByName(tour, "   "))
    }

    /** Two tours can share a checkpoint name, so one physical tag can serve both. */
    @Test fun theSameNameMatchesOnADifferentTour() {
        val otherTour = listOf(LogCheckpoint("log2", "x9", "Front Gate", 0))
        assertEquals("x9", matchByName(otherTour, "Front Gate")?.checkpointId)
    }

    @Test fun normalizeNameCollapsesCaseAndWhitespace() {
        assertEquals("front gate", normalizeName("  Front   Gate "))
        assertEquals("roof", normalizeName("ROOF"))
        assertEquals("a b c", normalizeName("a\n b\tc"))
    }
}
