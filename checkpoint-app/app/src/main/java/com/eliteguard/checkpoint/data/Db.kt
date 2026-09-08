package com.eliteguard.checkpoint.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local SQLite store. Reference data (sites, checkpoints, routes) is a cache of the server;
 * tour logs and scans are written here first and uploaded when the phone is online.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "eliteguard_tours.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE properties (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, address TEXT, zone TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE checkpoints (
                id TEXT PRIMARY KEY, property_id TEXT NOT NULL, name TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0, tag_uid TEXT, active INTEGER NOT NULL DEFAULT 1)"""
        )
        db.execSQL("CREATE INDEX idx_checkpoints_property ON checkpoints(property_id)")
        db.execSQL("CREATE INDEX idx_checkpoints_uid ON checkpoints(tag_uid)")
        db.execSQL(
            """CREATE TABLE tours (
                id TEXT PRIMARY KEY, property_id TEXT NOT NULL, name TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE tour_checkpoints (
                tour_id TEXT NOT NULL, checkpoint_id TEXT NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (tour_id, checkpoint_id))"""
        )
        db.execSQL(
            """CREATE TABLE tour_logs (
                id TEXT PRIMARY KEY, property_id TEXT NOT NULL, property_name TEXT NOT NULL,
                tour_id TEXT, tour_name TEXT, officer_id TEXT NOT NULL, officer_name TEXT NOT NULL,
                started_at TEXT NOT NULL, completed_at TEXT, status TEXT NOT NULL,
                total_checkpoints INTEGER NOT NULL DEFAULT 0, scanned_checkpoints INTEGER NOT NULL DEFAULT 0,
                device_id TEXT NOT NULL, synced INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL(
            """CREATE TABLE log_checkpoints (
                log_id TEXT NOT NULL, checkpoint_id TEXT NOT NULL, name TEXT NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (log_id, checkpoint_id))"""
        )
        db.execSQL(
            """CREATE TABLE tour_scans (
                id TEXT PRIMARY KEY, tour_log_id TEXT NOT NULL, checkpoint_id TEXT NOT NULL,
                checkpoint_name TEXT NOT NULL, scanned_at TEXT NOT NULL, tag_uid TEXT,
                is_duplicate INTEGER NOT NULL DEFAULT 0, synced INTEGER NOT NULL DEFAULT 0)"""
        )
        db.execSQL("CREATE INDEX idx_scans_log ON tour_scans(tour_log_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // First schema version; nothing to migrate yet.
    }

    // ---------------------------------------------------------------- reference data

    fun replaceReferenceData(properties: List<Property>, checkpoints: List<Checkpoint>, tours: List<Tour>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("properties", null, null)
            db.delete("checkpoints", null, null)
            db.delete("tours", null, null)
            db.delete("tour_checkpoints", null, null)
            for (p in properties) db.insert("properties", null, p.toValues())
            for (c in checkpoints) db.insert("checkpoints", null, c.toValues())
            for (t in tours) {
                db.insert("tours", null, ContentValues().apply {
                    put("id", t.id); put("property_id", t.propertyId); put("name", t.name); put("sort_order", t.sortOrder)
                })
                t.checkpointIds.forEachIndexed { index, cpId ->
                    db.insert("tour_checkpoints", null, ContentValues().apply {
                        put("tour_id", t.id); put("checkpoint_id", cpId); put("sort_order", index)
                    })
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun properties(): List<Property> =
        readableDatabase.query("properties", null, null, null, null, null, "name COLLATE NOCASE").use { c ->
            c.list { it.toProperty() }
        }

    fun property(id: String): Property? =
        readableDatabase.query("properties", null, "id = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) c.toProperty() else null
        }

    /** Active checkpoint counts keyed by property id. */
    fun checkpointCounts(): Map<String, Int> =
        readableDatabase.rawQuery("SELECT property_id, COUNT(*) FROM checkpoints WHERE active = 1 GROUP BY property_id", null).use { c ->
            val out = HashMap<String, Int>()
            while (c.moveToNext()) out[c.getString(0)] = c.getInt(1)
            out
        }

    fun checkpoints(propertyId: String): List<Checkpoint> =
        readableDatabase.query(
            "checkpoints", null, "property_id = ? AND active = 1", arrayOf(propertyId), null, null, "sort_order, name COLLATE NOCASE"
        ).use { c -> c.list { it.toCheckpoint() } }

    fun checkpoint(id: String): Checkpoint? =
        readableDatabase.query("checkpoints", null, "id = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) c.toCheckpoint() else null
        }

    fun checkpointByTag(tagUid: String): Checkpoint? =
        readableDatabase.query("checkpoints", null, "tag_uid = ? AND active = 1", arrayOf(tagUid), null, null, null).use { c ->
            if (c.moveToFirst()) c.toCheckpoint() else null
        }

    fun upsertCheckpoint(checkpoint: Checkpoint) {
        writableDatabase.insertWithOnConflict("checkpoints", null, checkpoint.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun tours(propertyId: String): List<Tour> {
        val db = readableDatabase
        val tours = db.query("tours", null, "property_id = ?", arrayOf(propertyId), null, null, "sort_order, name COLLATE NOCASE").use { c ->
            c.list { Tour(it.str("id"), it.str("property_id"), it.str("name"), it.int("sort_order"), emptyList()) }
        }
        return tours.map { t ->
            val ids = db.query("tour_checkpoints", arrayOf("checkpoint_id"), "tour_id = ?", arrayOf(t.id), null, null, "sort_order").use { c ->
                c.list { it.getString(0) }
            }
            t.copy(checkpointIds = ids)
        }
    }

    // ---------------------------------------------------------------- tour logs

    fun insertLog(log: TourLog, checklist: List<LogCheckpoint>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertWithOnConflict("tour_logs", null, log.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            for (cp in checklist) {
                db.insertWithOnConflict("log_checkpoints", null, ContentValues().apply {
                    put("log_id", cp.logId); put("checkpoint_id", cp.checkpointId); put("name", cp.name); put("sort_order", cp.sortOrder)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun updateLog(log: TourLog) {
        writableDatabase.update("tour_logs", log.toValues(), "id = ?", arrayOf(log.id))
    }

    fun log(id: String): TourLog? =
        readableDatabase.query("tour_logs", null, "id = ?", arrayOf(id), null, null, null).use { c ->
            if (c.moveToFirst()) c.toLog() else null
        }

    fun activeLog(propertyId: String): TourLog? =
        readableDatabase.query(
            "tour_logs", null, "property_id = ? AND status = ?", arrayOf(propertyId, LogStatus.IN_PROGRESS), null, null, "started_at DESC", "1"
        ).use { c -> if (c.moveToFirst()) c.toLog() else null }

    fun anyActiveLog(): TourLog? =
        readableDatabase.query("tour_logs", null, "status = ?", arrayOf(LogStatus.IN_PROGRESS), null, null, "started_at DESC", "1").use { c ->
            if (c.moveToFirst()) c.toLog() else null
        }

    fun logs(limit: Int = 200): List<TourLog> =
        readableDatabase.query("tour_logs", null, null, null, null, null, "started_at DESC", limit.toString()).use { c ->
            c.list { it.toLog() }
        }

    fun unsyncedLogs(): List<TourLog> =
        readableDatabase.query("tour_logs", null, "synced = 0", null, null, null, "started_at").use { c -> c.list { it.toLog() } }

    fun markLogSynced(id: String) {
        writableDatabase.update("tour_logs", ContentValues().apply { put("synced", 1) }, "id = ?", arrayOf(id))
    }

    fun logChecklist(logId: String): List<LogCheckpoint> =
        readableDatabase.query("log_checkpoints", null, "log_id = ?", arrayOf(logId), null, null, "sort_order").use { c ->
            c.list { LogCheckpoint(it.str("log_id"), it.str("checkpoint_id"), it.str("name"), it.int("sort_order")) }
        }

    // ---------------------------------------------------------------- scans

    fun insertScan(scan: TourScan) {
        writableDatabase.insertWithOnConflict("tour_scans", null, scan.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun scans(logId: String): List<TourScan> =
        readableDatabase.query("tour_scans", null, "tour_log_id = ?", arrayOf(logId), null, null, "scanned_at").use { c ->
            c.list { it.toScan() }
        }

    fun unsyncedScans(): List<TourScan> =
        readableDatabase.query("tour_scans", null, "synced = 0", null, null, null, "scanned_at").use { c -> c.list { it.toScan() } }

    fun markScanSynced(id: String) {
        writableDatabase.update("tour_scans", ContentValues().apply { put("synced", 1) }, "id = ?", arrayOf(id))
    }

    /** Number of local records (logs + scans) still waiting to upload. */
    fun pendingCount(): Int {
        val db = readableDatabase
        val logs = db.rawQuery("SELECT COUNT(*) FROM tour_logs WHERE synced = 0", null).use { it.moveToFirst(); it.getInt(0) }
        val scans = db.rawQuery("SELECT COUNT(*) FROM tour_scans WHERE synced = 0", null).use { it.moveToFirst(); it.getInt(0) }
        return logs + scans
    }

    // ---------------------------------------------------------------- mapping helpers

    private fun Property.toValues() = ContentValues().apply {
        put("id", id); put("name", name); put("address", address); put("zone", zone)
    }

    private fun Checkpoint.toValues() = ContentValues().apply {
        put("id", id); put("property_id", propertyId); put("name", name); put("sort_order", sortOrder)
        put("tag_uid", tagUid); put("active", if (active) 1 else 0)
    }

    private fun TourLog.toValues() = ContentValues().apply {
        put("id", id); put("property_id", propertyId); put("property_name", propertyName)
        put("tour_id", tourId); put("tour_name", tourName); put("officer_id", officerId); put("officer_name", officerName)
        put("started_at", startedAt); put("completed_at", completedAt); put("status", status)
        put("total_checkpoints", totalCheckpoints); put("scanned_checkpoints", scannedCheckpoints)
        put("device_id", deviceId); put("synced", if (synced) 1 else 0)
    }

    private fun TourScan.toValues() = ContentValues().apply {
        put("id", id); put("tour_log_id", tourLogId); put("checkpoint_id", checkpointId); put("checkpoint_name", checkpointName)
        put("scanned_at", scannedAt); put("tag_uid", tagUid); put("is_duplicate", if (isDuplicate) 1 else 0)
        put("synced", if (synced) 1 else 0)
    }

    private fun Cursor.toProperty() = Property(str("id"), str("name"), optStr("address"), optStr("zone"))

    private fun Cursor.toCheckpoint() = Checkpoint(
        str("id"), str("property_id"), str("name"), int("sort_order"), optStr("tag_uid"), int("active") == 1
    )

    private fun Cursor.toLog() = TourLog(
        id = str("id"), propertyId = str("property_id"), propertyName = str("property_name"),
        tourId = optStr("tour_id"), tourName = optStr("tour_name"), officerId = str("officer_id"), officerName = str("officer_name"),
        startedAt = str("started_at"), completedAt = optStr("completed_at"), status = str("status"),
        totalCheckpoints = int("total_checkpoints"), scannedCheckpoints = int("scanned_checkpoints"),
        deviceId = str("device_id"), synced = int("synced") == 1
    )

    private fun Cursor.toScan() = TourScan(
        id = str("id"), tourLogId = str("tour_log_id"), checkpointId = str("checkpoint_id"), checkpointName = str("checkpoint_name"),
        scannedAt = str("scanned_at"), tagUid = optStr("tag_uid"), isDuplicate = int("is_duplicate") == 1, synced = int("synced") == 1
    )

    private fun Cursor.str(column: String): String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.optStr(column: String): String? = getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.int(column: String): Int = getInt(getColumnIndexOrThrow(column))

    private inline fun <T> Cursor.list(map: (Cursor) -> T): List<T> {
        val out = ArrayList<T>(count)
        while (moveToNext()) out.add(map(this))
        return out
    }
}
