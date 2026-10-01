package com.eliteguard.checkpoint.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeFmt {
    private val time = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
    private val dateTime = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a").withZone(ZoneId.systemDefault())

    fun nowIso(): String = Instant.now().toString()

    fun time(iso: String?): String = parse(iso)?.let { time.format(it) } ?: "–"

    fun dateTime(iso: String?): String = parse(iso)?.let { dateTime.format(it) } ?: "–"

    fun parse(iso: String?): Instant? = try {
        if (iso.isNullOrBlank()) null else Instant.parse(normalize(iso))
    } catch (e: Exception) {
        null
    }

    /** PostgREST returns offsets like "+00:00" and may omit the "Z"; Instant.parse wants a zone. */
    private fun normalize(iso: String): String {
        if (iso.endsWith("Z")) return iso
        if (iso.length > 6 && (iso[iso.length - 6] == '+' || iso[iso.length - 6] == '-') && iso[iso.length - 3] == ':') {
            return java.time.OffsetDateTime.parse(iso).toInstant().toString()
        }
        if (iso.length > 3 && (iso[iso.length - 3] == '+' || iso[iso.length - 3] == '-')) {
            return java.time.OffsetDateTime.parse(iso + ":00").toInstant().toString()
        }
        return iso + "Z"
    }
}
