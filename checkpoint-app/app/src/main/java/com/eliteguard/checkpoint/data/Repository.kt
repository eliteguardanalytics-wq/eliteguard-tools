package com.eliteguard.checkpoint.data

import com.eliteguard.checkpoint.Config
import com.eliteguard.checkpoint.net.SupabaseClient
import com.eliteguard.checkpoint.util.TimeFmt
import com.eliteguard.checkpoint.util.bool
import com.eliteguard.checkpoint.util.int
import com.eliteguard.checkpoint.util.mapObjects
import com.eliteguard.checkpoint.util.reqStr
import com.eliteguard.checkpoint.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * Coordinates the local database and the server. Every method blocks and must run off the
 * main thread (see [com.eliteguard.checkpoint.util.Bg]).
 *
 * Offline strategy: tour logs and scans are written locally first with client-generated UUIDs
 * and uploaded with idempotent upserts, so a lost connection never loses or duplicates data.
 */
class Repository(
    val db: Db,
    private val api: SupabaseClient,
    private val session: Session,
    private val deviceId: String,
) {

    class ProfileMissing : IOException("No profile for this account")

    /** Thrown when a tag is already linked to a different checkpoint. */
    class TagTaken(val otherCheckpoint: Checkpoint?) : IOException("Tag already linked")

    enum class ScanOutcome { SCANNED, DUPLICATE, NOT_IN_ROUTE }

    // ------------------------------------------------------------------ auth

    fun signIn(username: String, password: String) {
        val typed = username.trim()
        api.signIn(typed.lowercase(), password)
        val userId = session.userId ?: throw ProfileMissing()
        val account = fetchAccount(userId)
        if (account == null) {
            api.signOut()
            throw ProfileMissing()
        }
        session.saveProfile(
            username = account.firstValue(Config.USERNAME_COLUMNS) ?: typed.lowercase(),
            displayName = account.firstValue(Config.DISPLAY_NAME_COLUMNS),
            role = account.str("role"),
        )
    }

    /**
     * Reads the signed-in officer's row from the accounts table. The whole row is selected and
     * the interesting columns are picked by name, so the app does not depend on that table
     * having any particular shape beyond a `role` column. Accounts keyed by `user_id` rather
     * than `id` are handled too.
     */
    private fun fetchAccount(userId: String): JSONObject? {
        val encoded = SupabaseClient.encode(userId)
        for (keyColumn in ACCOUNT_KEY_COLUMNS) {
            val rows = try {
                api.select(Config.ACCOUNTS_TABLE, "select=*&$keyColumn=eq.$encoded&limit=1")
            } catch (e: SupabaseClient.ApiException) {
                // A 400 means this table has no such column; try the next candidate.
                if (e.status == 400) continue else throw e
            }
            if (rows.length() > 0) return rows.getJSONObject(0)
        }
        return null
    }

    /** First non-blank value among [columns], or null when the row has none of them. */
    private fun JSONObject.firstValue(columns: List<String>): String? =
        columns.firstNotNullOfOrNull { column -> str(column)?.trim()?.takeIf { it.isNotEmpty() } }

    fun signOut() {
        api.signOut()
    }

    // ------------------------------------------------------------------ sync

    /** Downloads sites, checkpoints and routes and replaces the local cache. */
    fun refreshReferenceData() {
        // Selected with "*" so the app still works if the site table has no address or zone column.
        val properties = api.select(Config.PROPERTIES_TABLE, "select=*&order=name").mapObjects { it.toProperty() }
        val checkpoints = api.select("checkpoints", "select=id,property_id,name,sort_order,tag_uid,active&active=eq.true&order=sort_order,name")
            .mapObjects { it.toCheckpoint() }
        val tourRows = api.select("tours", "select=id,property_id,name,sort_order&active=eq.true&order=sort_order,name")
        val tourCheckpoints = api.select("tour_checkpoints", "select=tour_id,checkpoint_id,sort_order&order=sort_order")
        val byTour = HashMap<String, MutableList<String>>()
        tourCheckpoints.mapObjects { row ->
            byTour.getOrPut(row.reqStr("tour_id")) { ArrayList() }.add(row.reqStr("checkpoint_id"))
        }
        val tours = tourRows.mapObjects { row ->
            val id = row.reqStr("id")
            Tour(id, row.reqStr("property_id"), row.reqStr("name"), row.int("sort_order"), byTour[id] ?: emptyList())
        }
        db.replaceReferenceData(properties, checkpoints, tours)
    }

    /**
     * Uploads every log and scan not yet on the server. Returns the number of records uploaded.
     * Network failures are swallowed (the data stays queued); auth failures propagate.
     */
    fun pushPending(): Int {
        var uploaded = 0
        val failedLogs = HashSet<String>()
        for (log in db.unsyncedLogs()) {
            try {
                api.upsert("tour_logs", JSONArray().put(log.toJson()))
                db.markLogSynced(log.id)
                uploaded++
            } catch (e: SupabaseClient.AuthException) {
                throw e
            } catch (e: IOException) {
                failedLogs.add(log.id)
            }
        }
        val scans = db.unsyncedScans().filter { it.tourLogId !in failedLogs }
        if (scans.isNotEmpty()) {
            for (batch in scans.chunked(100)) {
                try {
                    api.upsert("tour_scans", JSONArray().also { arr -> batch.forEach { arr.put(it.toJson()) } })
                    batch.forEach { db.markScanSynced(it.id) }
                    uploaded += batch.size
                } catch (e: SupabaseClient.AuthException) {
                    throw e
                } catch (e: IOException) {
                    // Leave queued for the next attempt.
                }
            }
        }
        return uploaded
    }

    /** Best-effort upload used right after a local write; never throws. */
    private fun tryPush() {
        try {
            pushPending()
        } catch (e: Exception) {
            // Will be retried from the sites screen.
        }
    }

    // ------------------------------------------------------------------ tours

    /** The checkpoints an officer must visit for the chosen route (or every active checkpoint). */
    fun routeCheckpoints(property: Property, tour: Tour?): List<Checkpoint> {
        val all = db.checkpoints(property.id)
        if (tour == null) return all
        val byId = all.associateBy { it.id }
        return tour.checkpointIds.mapNotNull { byId[it] }
    }

    fun startTour(property: Property, tour: Tour?): TourLog {
        val checkpoints = routeCheckpoints(property, tour)
        val log = TourLog(
            id = UUID.randomUUID().toString(),
            propertyId = property.id,
            propertyName = property.name,
            tourId = tour?.id,
            tourName = tour?.name,
            officerId = session.userId ?: "",
            officerName = session.officerName,
            startedAt = TimeFmt.nowIso(),
            completedAt = null,
            status = LogStatus.IN_PROGRESS,
            totalCheckpoints = checkpoints.size,
            scannedCheckpoints = 0,
            deviceId = deviceId,
            synced = false,
        )
        val checklist = checkpoints.mapIndexed { index, cp -> LogCheckpoint(log.id, cp.id, cp.name, index) }
        db.insertLog(log, checklist)
        tryPush()
        return log
    }

    /** Finds the checkpoint a tapped tag belongs to, by tag serial first and then by the id written on the tag. */
    fun resolveTag(tagUid: String, ndefCheckpointId: String?): Checkpoint? {
        db.checkpointByTag(tagUid)?.let { return it }
        if (ndefCheckpointId != null) return db.checkpoint(ndefCheckpointId)?.takeIf { it.active }
        return null
    }

    /** Records a tap during an active tour and returns what happened plus the refreshed log. */
    fun recordScan(log: TourLog, checkpoint: Checkpoint, tagUid: String): Pair<ScanOutcome, TourLog> {
        val checklist = db.logChecklist(log.id)
        if (checklist.none { it.checkpointId == checkpoint.id }) return ScanOutcome.NOT_IN_ROUTE to log
        val alreadyScanned = db.scans(log.id).any { it.checkpointId == checkpoint.id && !it.isDuplicate }
        val scan = TourScan(
            id = UUID.randomUUID().toString(),
            tourLogId = log.id,
            checkpointId = checkpoint.id,
            checkpointName = checkpoint.name,
            scannedAt = TimeFmt.nowIso(),
            tagUid = tagUid,
            isDuplicate = alreadyScanned,
            synced = false,
        )
        db.insertScan(scan)
        // Recount from the database rather than incrementing, so rapid taps can never drift.
        val scannedCount = db.scans(log.id).filter { !it.isDuplicate }.map { it.checkpointId }.toSet().size
        val updated = (db.log(log.id) ?: log).copy(scannedCheckpoints = scannedCount, synced = false)
        db.updateLog(updated)
        tryPush()
        return (if (alreadyScanned) ScanOutcome.DUPLICATE else ScanOutcome.SCANNED) to updated
    }

    fun endTour(log: TourLog): TourLog {
        val scanned = db.scans(log.id).filter { !it.isDuplicate }.map { it.checkpointId }.toSet().size
        val updated = log.copy(
            completedAt = TimeFmt.nowIso(),
            status = if (scanned >= log.totalCheckpoints) LogStatus.COMPLETED else LogStatus.INCOMPLETE,
            scannedCheckpoints = scanned,
            synced = false,
        )
        db.updateLog(updated)
        tryPush()
        return updated
    }

    // ------------------------------------------------------------------ checkpoint setup (managers)

    fun createCheckpoint(property: Property, name: String): Checkpoint {
        val nextOrder = (db.checkpoints(property.id).maxOfOrNull { it.sortOrder } ?: 0) + 1
        val row = JSONObject()
            .put("property_id", property.id)
            .put("name", name.trim())
            .put("sort_order", nextOrder)
            .put("active", true)
        val created = api.insert("checkpoints", row).toCheckpoint()
        db.upsertCheckpoint(created)
        return created
    }

    fun renameCheckpoint(checkpoint: Checkpoint, name: String): Checkpoint {
        val rows = api.update("checkpoints", "id=eq.${SupabaseClient.encode(checkpoint.id)}", JSONObject().put("name", name.trim()))
        val updated = if (rows.length() > 0) rows.getJSONObject(0).toCheckpoint() else checkpoint.copy(name = name.trim())
        db.upsertCheckpoint(updated)
        return updated
    }

    fun deactivateCheckpoint(checkpoint: Checkpoint) {
        api.update("checkpoints", "id=eq.${SupabaseClient.encode(checkpoint.id)}", JSONObject().put("active", false))
        db.upsertCheckpoint(checkpoint.copy(active = false))
    }

    /** Links an NFC tag serial to a checkpoint. Throws [TagTaken] if the tag belongs to another checkpoint. */
    fun linkTag(checkpoint: Checkpoint, tagUid: String): Checkpoint {
        val existing = api.select("checkpoints", "select=id,property_id,name,sort_order,tag_uid,active&tag_uid=eq.${SupabaseClient.encode(tagUid)}&limit=1")
        if (existing.length() > 0) {
            val other = existing.getJSONObject(0).toCheckpoint()
            if (other.id != checkpoint.id) throw TagTaken(other)
        }
        val rows = try {
            api.update("checkpoints", "id=eq.${SupabaseClient.encode(checkpoint.id)}", JSONObject().put("tag_uid", tagUid))
        } catch (e: SupabaseClient.ApiException) {
            if (e.isConflict) throw TagTaken(null) else throw e
        }
        val updated = if (rows.length() > 0) rows.getJSONObject(0).toCheckpoint() else checkpoint.copy(tagUid = tagUid)
        db.upsertCheckpoint(updated)
        return updated
    }

    // ------------------------------------------------------------------ JSON mapping

    private fun JSONObject.toProperty() = Property(reqStr("id"), str("name") ?: "(unnamed)", str("address"), str("zone"))

    private companion object {
        /** Column names tried, in order, when looking up an account by its Supabase Auth user id. */
        val ACCOUNT_KEY_COLUMNS = listOf("id", "user_id", "auth_user_id")
    }

    private fun JSONObject.toCheckpoint() = Checkpoint(
        id = reqStr("id"),
        propertyId = reqStr("property_id"),
        name = str("name") ?: "(unnamed)",
        sortOrder = int("sort_order"),
        tagUid = str("tag_uid"),
        active = bool("active", true),
    )
}
