package com.eliteguard.checkpoint.nfc

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.io.IOException

/** Helpers for reading and enrolling checkpoint tags. */
object NfcTags {

    /** NDEF external type written on enrolled tags: `eliteguard.internal:checkpoint` with the checkpoint id as payload. */
    const val EXT_DOMAIN = "eliteguard.internal"
    const val EXT_TYPE = "checkpoint"

    /** Reader-mode flags: every common tag technology, and no system tap sound (the app plays its own). */
    const val READER_FLAGS: Int = NfcAdapter.FLAG_READER_NFC_A or
        NfcAdapter.FLAG_READER_NFC_B or
        NfcAdapter.FLAG_READER_NFC_F or
        NfcAdapter.FLAG_READER_NFC_V or
        NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

    /** The tag's serial number as upper-case hex, e.g. `04A3B2C1D95E80`. */
    fun uidHex(tag: Tag): String = tag.id.joinToString("") { String.format("%02X", it) }

    /** The checkpoint id stored on the tag, if it was enrolled by this app. */
    fun readCheckpointId(tag: Tag): String? {
        val ndef = Ndef.get(tag) ?: return null
        val message = try {
            ndef.connect()
            ndef.ndefMessage ?: ndef.cachedNdefMessage
        } catch (e: Exception) {
            ndef.cachedNdefMessage
        } finally {
            try {
                ndef.close()
            } catch (e: IOException) {
                // ignore
            }
        }
        return checkpointIdFrom(message)
    }

    fun checkpointIdFrom(message: NdefMessage?): String? {
        val records = message?.records ?: return null
        val wanted = "$EXT_DOMAIN:$EXT_TYPE"
        for (record in records) {
            if (record.tnf == NdefRecord.TNF_EXTERNAL_TYPE && String(record.type, Charsets.US_ASCII).equals(wanted, ignoreCase = true)) {
                return String(record.payload, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /**
     * Writes the checkpoint id onto the tag so it can still be recognised if the serial ever
     * changes or is unreadable. Returns true on success; false if the tag cannot be written
     * (read-only, not NDEF capable, too small). Linking by serial works either way.
     */
    fun writeCheckpointId(tag: Tag, checkpointId: String): Boolean {
        val record = NdefRecord.createExternal(EXT_DOMAIN, EXT_TYPE, checkpointId.toByteArray(Charsets.UTF_8))
        val message = NdefMessage(arrayOf(record))
        Ndef.get(tag)?.let { ndef ->
            return try {
                ndef.connect()
                if (!ndef.isWritable || ndef.maxSize < message.toByteArray().size) return false
                ndef.writeNdefMessage(message)
                true
            } catch (e: Exception) {
                false
            } finally {
                try {
                    ndef.close()
                } catch (e: IOException) {
                    // ignore
                }
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
                try {
                    formatable.close()
                } catch (e: IOException) {
                    // ignore
                }
            }
        }
        return false
    }
}
