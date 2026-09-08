package com.eliteguard.checkpoint.data

import org.json.JSONObject

/** A client site. Mirrors the `properties` table used by the web tools. */
data class Property(
    val id: String,
    val name: String,
    val address: String?,
    val zone: String?,
)

/** A named NFC checkpoint at a site. `tagUid` is the NFC tag serial once the tag is enrolled. */
data class Checkpoint(
    val id: String,
    val propertyId: String,
    val name: String,
    val sortOrder: Int,
    val tagUid: String?,
    val active: Boolean,
)

/** An optional predefined route: a named subset (and order) of a site's checkpoints. */
data class Tour(
    val id: String,
    val propertyId: String,
    val name: String,
    val sortOrder: Int,
    val checkpointIds: List<String>,
)

object LogStatus {
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"
    const val INCOMPLETE = "incomplete"
}

/** One executed tour by one officer. */
data class TourLog(
    val id: String,
    val propertyId: String,
    val propertyName: String,
    val tourId: String?,
    val tourName: String?,
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
        .put("tour_id", tourId ?: JSONObject.NULL)
        .put("officer_id", officerId)
        .put("officer_name", officerName)
        .put("started_at", startedAt)
        .put("completed_at", completedAt ?: JSONObject.NULL)
        .put("status", status)
        .put("total_checkpoints", totalCheckpoints)
        .put("scanned_checkpoints", scannedCheckpoints)
        .put("device_id", deviceId)
}

/** A checkpoint that belongs to a tour log's checklist, frozen when the tour started. */
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
