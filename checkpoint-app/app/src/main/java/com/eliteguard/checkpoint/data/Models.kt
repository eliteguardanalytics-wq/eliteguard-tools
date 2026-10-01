package com.eliteguard.checkpoint.data

import org.json.JSONObject

/** A client site. Mirrors the `properties` table in the incident reporting project. */
data class Property(
    val id: String,
    val name: String,
    val address: String?,
    val zone: String?,
)

/**
 * A named tour (patrol route) at a site. The admin portal creates these under a site, then
 * adds the tour's checkpoints. `propertyName` is carried along so the tour picker can group
 * tours by site without a second lookup.
 */
data class Tour(
    val id: String,
    val propertyId: String,
    val propertyName: String,
    val name: String,
    val sortOrder: Int,
)

/**
 * A named checkpoint on one tour. The name is the identity: it is what the admin writes onto
 * the NFC tag, and what a scan is matched against during a tour.
 *
 * `tagUid` and `tagWrittenAt` record the last tag this checkpoint's name was written to. They
 * are informational only — matching never depends on them — and let the setup screen show which
 * checkpoints still need a tag.
 */
data class Checkpoint(
    val id: String,
    val tourId: String,
    val name: String,
    val sortOrder: Int,
    val tagUid: String?,
    val tagWrittenAt: String?,
    val active: Boolean,
) {
    val hasTag: Boolean get() = tagWrittenAt != null
}

object LogStatus {
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"
    const val INCOMPLETE = "incomplete"
}

/** One tour walked by one officer. */
data class TourLog(
    val id: String,
    val propertyId: String,
    val propertyName: String,
    val tourId: String,
    val tourName: String,
    val officerId: String,
    val officerName: String,
    val startedAt: String,
    val completedAt: String?,
    val status: String,
    val totalCheckpoints: Int,
    val scannedCheckpoints: Int,
    val deviceId: String,
    val synced: Boolean,
) {
    val isActive: Boolean get() = status == LogStatus.IN_PROGRESS

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("property_id", propertyId)
        .put("tour_id", tourId)
        .put("officer_id", officerId)
        .put("officer_name", officerName)
        .put("started_at", startedAt)
        .put("completed_at", completedAt ?: JSONObject.NULL)
        .put("status", status)
        .put("total_checkpoints", totalCheckpoints)
        .put("scanned_checkpoints", scannedCheckpoints)
        .put("device_id", deviceId)
}

/** A checkpoint on a tour log's checklist, frozen as it was when the tour started. */
data class LogCheckpoint(
    val logId: String,
    val checkpointId: String,
    val name: String,
    val sortOrder: Int,
)

/** One tag tap during a tour. */
data class TourScan(
    val id: String,
    val tourLogId: String,
    val checkpointId: String,
    val checkpointName: String,
    val scannedAt: String,
    val tagUid: String?,
    val isDuplicate: Boolean,
    val synced: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("tour_log_id", tourLogId)
        .put("checkpoint_id", checkpointId)
        .put("checkpoint_name", checkpointName)
        .put("scanned_at", scannedAt)
        .put("tag_uid", tagUid ?: JSONObject.NULL)
        .put("is_duplicate", isDuplicate)
}

/**
 * One row of the officer's live checklist. Unscanned checkpoints sort first in the order the
 * admin defined them; scanned ones drop to the bottom in the order they were scanned.
 */
data class ChecklistRow(
    val checkpointId: String,
    val name: String,
    val sortOrder: Int,
    val scannedAt: String?,
) {
    val isScanned: Boolean get() = scannedAt != null
}

/** Normalises a checkpoint name for matching, so casing and stray spaces never cause a miss. */
fun normalizeName(name: String): String = name.trim().replace(WHITESPACE, " ").lowercase()

private val WHITESPACE = Regex("\\s+")

/**
 * Builds the officer's live checklist.
 *
 * Everything still outstanding comes first, in the order the administrator defined. Anything
 * scanned drops to the bottom in the order it was scanned, so the next checkpoint to visit is
 * always at the top of the screen.
 *
 * @param checklist the tour's checkpoints as frozen when the tour started
 * @param firstScanAt when each checkpoint was first scanned, keyed by checkpoint id
 */
fun buildChecklist(checklist: List<LogCheckpoint>, firstScanAt: Map<String, String>): List<ChecklistRow> =
    checklist
        .map { ChecklistRow(it.checkpointId, it.name, it.sortOrder, firstScanAt[it.checkpointId]) }
        .sortedWith(
            compareBy(
                { it.isScanned },            // outstanding above scanned
                { it.scannedAt ?: "" },      // scanned, in the order they were scanned
                { it.sortOrder },            // outstanding, in the administrator's order
                { it.name },
            )
        )

/**
 * Finds the checkpoint a tag belongs to by the name written on it. Matching ignores case and
 * collapsed whitespace; a blank or unrecognised name matches nothing.
 */
fun matchByName(checklist: List<LogCheckpoint>, tagName: String?): LogCheckpoint? {
    if (tagName.isNullOrBlank()) return null
    val wanted = normalizeName(tagName)
    return checklist.firstOrNull { normalizeName(it.name) == wanted }
}
