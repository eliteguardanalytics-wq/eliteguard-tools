package com.eliteguard.checkpoint.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.io.IOException

/**
 * Reading and writing checkpoint tags.
 *
 * A checkpoint's **name** is what lives on the tag. An admin selects a checkpoint in the app and
 * taps a tag; the name is written onto it and stays there until an admin overwrites it. During a
 * tour, the name read back off the tag is matched against the checkpoints on that tour.
 *
 * Each tag carries the name twice: once in an app-specific external record (what this app reads)
 * and once in a plain text record, so a generic NFC reader shows a human the checkpoint name too.
 * Tags are never write-locked, so an admin can always re-point one.
 */
object NfcTags {

    /** NDEF external type on enrolled tags: `eliteguard.internal:checkpoint`, payload = the name. */
    const val EXT_DOMAIN = "eliteguard.internal"
    const val EXT_TYPE = "checkpoint"

    /** Reader-mode flags: every common tag technology, and no system tap sound (the app plays its own). */
    const val READER_FLAGS: Int = NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or
        NfcAdapter.FLAG_READER_NFC_V or
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

    /** The tag's serial number as upper-case hex, e.g. `04A3B2C1D95E80`. Recorded for audit only. */
    fun uidHex(tag: Tag): String = tag.id.joinToString("") { String.format("%02X", it) }

    /** The checkpoint name stored on the tag, or null if the tag carries no name. */
    fun readName(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        val message = try {
            ndef.connect()
            ndef.ndefMessage ?: ndef.cachedNdefMessage
        } catch (e: Exception) {
            ndef.cachedNdefMessage
        } finally {
            closeQuietly(ndef)
        }
        return nameFrom(message)
    }

    /** Pulls the checkpoint name out of an NDEF message: the external record first, then any text record. */
    fun nameFrom(message: NdefMessage?): String? {
        val records = message?.records ?: return null
        val wanted = "$EXT_DOMAIN:$EXT_TYPE"
        for (record in records) {
            if (record.tnf == NdefRecord.TNF_EXTERNAL_TYPE && String(record.type, Charsets.US_ASCII).equals(wanted, ignoreCase = true)) {
                return String(record.payload, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
            }
        }
        for (record in records) {
            textOf(record)?.let { return it }
        }
        return null
    }

    /** Decodes an RTD_TEXT record, whose payload is a status byte, a language code, then the text. */
    private fun textOf(record: NdefRecord): String? {
        if (record.tnf != NdefRecord.TNF_WELL_KNOWN) return null
        if (!record.type.contentEquals(NdefRecord.RTD_TEXT)) return null
        val payload = record.payload
        if (payload.isEmpty()) return null
        val status = payload[0].toInt()
        val languageLength = status and 0x3F
        val charset = if (status and 0x80 == 0) Charsets.UTF_8 else Charsets.UTF_16
        val start = 1 + languageLength
        if (start > payload.size) return null
        return String(payload, start, payload.size - start, charset).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Writes [name] onto the tag. Returns true when the tag now carries the name.
     *
     * Fails (returns false) on a tag that is read-only, not NDEF capable, or too small for the
     * name. Nothing is locked, so an admin can point the same tag at a different checkpoint later.
     */
    fun writeName(tag: Tag, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val message = NdefMessage(
            arrayOf(
                NdefRecord.createExternal(EXT_DOMAIN, EXT_TYPE, trimmed.toByteArray(Charsets.UTF_8)),
                NdefRecord.createTextRecord("en", trimmed),
            )
        )
        Ndef.get(tag)?.let { ndef ->
            return try {
                ndef.connect()
                if (!ndef.isWritable || ndef.maxSize < message.toByteArray().size) return false
                ndef.writeNdefMessage(message)
                true
            } catch (e: Exception) {
                false
            } finally {
                closeQuietly(ndef)
            }
        }
        NdefFormatable.get(tag)?.let { formatable ->
            return try {
                formatable.connect()
                formatable.format(message)
                true
            } catch (e: Exception) {
                false
            } finally {
                closeQuietly(formatable)
            }
        }
        return false
    }

    private fun closeQuietly(closeable: java.io.Closeable) {
        try {
            closeable.close()
        } catch (e: IOException) {
            // Nothing useful to do; the tag has usually left the field.
        }
    }
}
