package com.eliteguard.checkpoint.data

import com.eliteguard.checkpoint.Config
import com.eliteguard.checkpoint.net.SupabaseClient
import com.eliteguard.checkpoint.net.TenantConfig
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
    private val device: Device,
    private val deviceId: String,
) {

    class ProfileMissing : IOException("No account for this login")

    /** The phone has not been enrolled against a site yet. */
    class NotEnrolled : IOException("This device is not assigned to a site")

    /** The licence key did not match the portal host it was entered with. */
    class LicenseRejected : IOException("Licence key not recognised for that portal")

    /** The site this phone was activated against. */
    data class EnrolledSite(val propertyId: String, val propertyName: String, val backendFromHost: Boolean)

    /** What a tag tap meant. */
    enum class ScanOutcome {
        /** Matched a checkpoint on this tour that had not been scanned yet. */
        SCANNED,

        /** Matched a checkpoint on this tour that was already scanned. */
        DUPLICATE,

        /** The tag carries a name, but no checkpoint on this tour has it. */
        NOT_ON_TOUR,

        /** The tag carries no checkpoint name at all; an admin has not set it up. */
        BLANK,
    }

    /** The result of a tag tap: what happened, which checkpoint (if any), and the refreshed log. */
    data class ScanResult(
        val outcome: ScanOutcome,
        val checkpointName: String?,
        val log: TourLog,
    )

    // ------------------------------------------------------------------ enrolment

    /**
     * Ties this phone to one site. Resolves the portal host to a backend, asks that backend
     * whether the licence key belongs to the host, and only stores anything once it says yes.
     *
     * Done once per phone. Signing out does not undo it.
     */
    fun enrol(typedHost: String, typedLicense: String): EnrolledSite {
        val host = Device.normalizeHost(typedHost) ?: throw LicenseRejected()
        val license = Device.normalizeLicense(typedLicense)
        if (license.isEmpty()) throw LicenseRejected()

        val backend = TenantConfig.resolve(host)
        val row = api.verifyLicense(backend.url, backend.anonKey, license, host) ?: throw LicenseRejected()
        val propertyId = row.str("property_id") ?: throw LicenseRejected()
        val propertyName = row.str("property_name") ?: "(unnamed site)"

        // Anything cached from a previous enrolment belongs to a different site.
        db.replaceReferenceData(emptyList(), emptyList(), emptyList())
        device.enrol(host, license, propertyId, propertyName, backend.url, backend.anonKey)
        return EnrolledSite(propertyId, propertyName, backend.fromHost)
    }

    /**
     * Tells the server this phone exists, so the portal can list the devices at a site. Best
     * effort and attempted once: nothing in the app depends on it succeeding.
     */
    private fun registerDeviceOnce() {
        if (device.registered) return
        val propertyId = device.propertyId ?: return
        try {
            // A function, not a direct insert: an officer may register a device but only an admin
            // may read the fleet, and an upsert would need to read the row it conflicts with.
            api.rpc(
                "register_device",
                JSONObject()
                    .put("p_device_id", deviceId)
                    .put("p_property_id", propertyId)
                    .put("p_portal_host", device.portalHost ?: JSONObject.NULL)
                    .put("p_app_version", Config.VERSION_NAME),
            )
            device.registered = true
        } catch (e: Exception) {
            // The portal simply will not list this phone yet.
        }
    }

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
        registerDeviceOnce()
    }

    fun signOut() {
        api.signOut()
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

    // ------------------------------------------------------------------ sync

    /**
     * Downloads the enrolled site, its tours and their checkpoints, and replaces the local cache.
     * Everything is filtered to the one site this phone belongs to, so a phone can never show
     * another site even if the account behind it can see more.
     */
    fun refreshReferenceData() {
        val propertyId = device.propertyId ?: throw NotEnrolled()
        val encodedProperty = SupabaseClient.encode(propertyId)
        // Selected with "*" so the app still works if the site table has no address or zone column.
        val properties = api.select(Config.PROPERTIES_TABLE, "select=*&id=eq.$encodedProperty")
            .mapObjects { it.toProperty() }
        val siteName = properties.firstOrNull()?.name ?: device.siteName
        val tours = api
            .select("tours", "select=id,property_id,name,sort_order&active=eq.true&property_id=eq.$encodedProperty&order=sort_order,name")
            .mapObjects { row ->
                Tour(
                    id = row.reqStr("id"),
                    propertyId = row.reqStr("property_id"),
                    propertyName = siteName,
                    name = row.str("name") ?: "(unnamed tour)",
                    sortOrder = row.int("sort_order"),
                )
            }
        val checkpoints = if (tours.isEmpty()) {
            emptyList()
        } else {
            val ids = tours.joinToString(",") { SupabaseClient.encode(it.id) }
            api.select(
                "checkpoints",
                "select=id,tour_id,name,sort_order,tag_uid,tag_written_at,active&active=eq.true&tour_id=in.($ids)&order=sort_order,name",
            ).mapObjects { it.toCheckpoint() }
        }
        db.replaceReferenceData(properties, tours, checkpoints)
        registerDeviceOnce()
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
            // Will be retried from the home screen.
        }
    }

    // ------------------------------------------------------------------ tours

    fun startTour(tour: Tour): TourLog {
        val checkpoints = db.checkpoints(tour.id)
        val log = TourLog(
            id = UUID.randomUUID().toString(),
            propertyId = tour.propertyId,
            propertyName = tour.propertyName,
            tourId = tour.id,
            tourName = tour.name,
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

    /**
     * Records a tag tap during a tour. [tagName] is the checkpoint name read off the tag, which is
     * matched against this tour's checklist ignoring case and stray spaces.
     */
    fun recordScan(log: TourLog, tagName: String?, tagUid: String): ScanResult {
        if (tagName.isNullOrBlank()) return ScanResult(ScanOutcome.BLANK, null, log)
        val match = matchByName(db.logChecklist(log.id), tagName)
            ?: return ScanResult(ScanOutcome.NOT_ON_TOUR, tagName.trim(), log)

        val alreadyScanned = db.scans(log.id).any { it.checkpointId == match.checkpointId && !it.isDuplicate }
        db.insertScan(
            TourScan(
                id = UUID.randomUUID().toString(),
                tourLogId = log.id,
                checkpointId = match.checkpointId,
                checkpointName = match.name,
                scannedAt = TimeFmt.nowIso(),
                tagUid = tagUid,
                isDuplicate = alreadyScanned,
                synced = false,
            )
        )
        // Recount from the database rather than incrementing, so rapid taps can never drift.
        val scannedCount = db.scans(log.id).filter { !it.isDuplicate }.map { it.checkpointId }.toSet().size
        val updated = (db.log(log.id) ?: log).copy(scannedCheckpoints = scannedCount, synced = false)
        db.updateLog(updated)
        tryPush()
        return ScanResult(
            outcome = if (alreadyScanned) ScanOutcome.DUPLICATE else ScanOutcome.SCANNED,
            checkpointName = match.name,
            log = updated,
        )
    }

    /** Ends the tour, whether or not every checkpoint was scanned. */
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

    // ------------------------------------------------------------------ tag setup (admins)

    /**
     * Records that this checkpoint's name has been written onto a physical tag. The tag serial is
     * kept for audit and so the setup screen can show which checkpoints still need a tag; matching
     * during a tour is always by name, never by serial.
     */
    fun recordTagWritten(checkpoint: Checkpoint, tagUid: String): Checkpoint {
        val writtenAt = TimeFmt.nowIso()
        val rows = api.update(
            "checkpoints",
            "id=eq.${SupabaseClient.encode(checkpoint.id)}",
            JSONObject().put("tag_uid", tagUid).put("tag_written_at", writtenAt),
        )
        val updated = if (rows.length() > 0) {
            rows.getJSONObject(0).toCheckpoint()
        } else {
            checkpoint.copy(tagUid = tagUid, tagWrittenAt = writtenAt)
        }
        db.upsertCheckpoint(updated)
        return updated
    }

    /** Creates a tour at the site this phone is enrolled against. */
    fun createTour(name: String): Tour {
        val propertyId = device.propertyId ?: throw NotEnrolled()
        val nextOrder = (db.tours().maxOfOrNull { it.sortOrder } ?: 0) + 1
        val row = JSONObject()
            .put("property_id", propertyId)
            .put("name", name.trim())
            .put("sort_order", nextOrder)
            .put("active", true)
        val created = api.insert("tours", row)
        val tour = Tour(
            id = created.reqStr("id"),
            propertyId = propertyId,
            propertyName = device.siteName,
            name = created.str("name") ?: name.trim(),
            sortOrder = created.int("sort_order", nextOrder),
        )
        db.upsertTour(tour)
        return tour
    }

    /** Creates one checkpoint on a tour and returns it, ready for its tag to be programmed. */
    fun createCheckpoint(tour: Tour, name: String): Checkpoint =
        createCheckpoints(tour, listOf(name)).firstOrNull()
            ?: throw IOException("A checkpoint named \"${name.trim()}\" is already on this tour")

    /**
     * Creates checkpoints on a tour from a list of names, in the order given. The admin portal is
     * the usual place to do this; the app keeps it so a site can be set up from the field.
     */
    fun createCheckpoints(tour: Tour, names: List<String>): List<Checkpoint> {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return emptyList()
        val existing = db.checkpoints(tour.id)
        var nextOrder = (existing.maxOfOrNull { it.sortOrder } ?: 0) + 1
        val taken = existing.map { normalizeName(it.name) }.toMutableSet()
        val rows = JSONArray()
        for (name in clean) {
            if (!taken.add(normalizeName(name))) continue
            rows.put(
                JSONObject()
                    .put("tour_id", tour.id)
                    .put("name", name)
                    .put("sort_order", nextOrder++)
                    .put("active", true)
            )
        }
        if (rows.length() == 0) return emptyList()
        val created = api.upsert("checkpoints", rows).mapObjects { it.toCheckpoint() }
        created.forEach { db.upsertCheckpoint(it) }
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

    // ------------------------------------------------------------------ JSON mapping

    private fun JSONObject.toProperty() = Property(reqStr("id"), str("name") ?: "(unnamed)", str("address"), str("zone"))

    private fun JSONObject.toCheckpoint() = Checkpoint(
        id = reqStr("id"),
        tourId = reqStr("tour_id"),
        name = str("name") ?: "(unnamed)",
        sortOrder = int("sort_order"),
        tagUid = str("tag_uid"),
        tagWrittenAt = str("tag_written_at"),
        active = bool("active", true),
    )

    private companion object {
        /** Column names tried, in order, when looking up an account by its Supabase Auth user id. */
        val ACCOUNT_KEY_COLUMNS = listOf("id", "user_id", "auth_user_id")
    }
}
