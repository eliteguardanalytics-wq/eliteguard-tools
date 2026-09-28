package com.eliteguard.checkpoint.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.Settings
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toolbar
import com.eliteguard.checkpoint.App
import com.eliteguard.checkpoint.R
import com.eliteguard.checkpoint.data.Checkpoint
import com.eliteguard.checkpoint.data.Tour
import com.eliteguard.checkpoint.data.normalizeName
import com.eliteguard.checkpoint.net.SupabaseClient
import com.eliteguard.checkpoint.nfc.NfcTags
import com.eliteguard.checkpoint.util.Bg
import java.io.IOException

/**
 * Add a checkpoint and program its tag in one pass: type the name, press Program Tag, hold the
 * tag to the phone.
 *
 * The checkpoint is created the moment Program Tag is pressed, before the tag is touched. If the
 * write then fails, the checkpoint still exists with no tag, and it can be picked up again from
 * Program Existing Tag. Losing the name would be the worse outcome.
 */
class AddTagActivity : Activity() {

    private val app by lazy { App.get(this) }
    private val repo by lazy { app.repo }

    private lateinit var tour: Tour
    private lateinit var name: EditText
    private lateinit var program: Button
    private lateinit var status: TextView
    private lateinit var nfcBanner: TextView

    private var nfc: NfcAdapter? = null

    /** The checkpoint waiting for its tag. Non-null means the reader is armed. */
    private var pending: Checkpoint? = null
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loaded = intent.getStringExtra(EXTRA_TOUR_ID)?.let { app.db.tour(it) }
        if (loaded == null || !app.session.canManageCheckpoints) {
            finish()
            return
        }
        tour = loaded
        setContentView(R.layout.activity_add_tag)
        setupToolbar(findViewById<Toolbar>(R.id.toolbar), getString(R.string.add_tag_title), showUp = true)
        actionBar?.subtitle = "${tour.propertyName} · ${tour.name}"
        name = findViewById(R.id.name)
        program = findViewById(R.id.program)
        status = findViewById(R.id.status)
        nfcBanner = findViewById(R.id.nfc_banner)
        nfcBanner.setOnClickListener { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
        program.setOnClickListener { createThenArm() }
        nfc = NfcAdapter.getDefaultAdapter(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (!::tour.isInitialized) return
        val adapter = nfc
        when {
            adapter == null -> {
                nfcBanner.text = getString(R.string.nfc_missing); nfcBanner.visibility = View.VISIBLE
            }
            !adapter.isEnabled -> {
                nfcBanner.text = getString(R.string.nfc_disabled) + "\n" + getString(R.string.nfc_open_settings)
                nfcBanner.visibility = View.VISIBLE
            }
            else -> {
                nfcBanner.visibility = View.GONE
                adapter.enableReaderMode(this, ::onTagDiscovered, NfcTags.READER_FLAGS, null)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::tour.isInitialized) nfc?.disableReaderMode(this)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun render() {
        val waiting = pending
        if (waiting == null) {
            status.text = getString(R.string.add_tag_hint)
            status.setBackgroundResource(R.drawable.bg_banner_warning)
            status.setTextColor(getColor(R.color.accent_text))
            program.text = getString(R.string.add_tag_program)
            program.isEnabled = true
            name.isEnabled = true
        } else {
            status.text = getString(R.string.add_tag_waiting, waiting.name)
            status.setBackgroundResource(R.drawable.bg_banner_success)
            status.setTextColor(getColor(R.color.success))
            program.text = getString(R.string.add_tag_waiting_button)
            program.isEnabled = false
            name.isEnabled = false
        }
    }

    /** Creates the checkpoint, then arms the reader for the tap that writes its name. */
    private fun createThenArm() {
        val typed = name.text.toString().trim()
        if (typed.isEmpty()) {
            toast(getString(R.string.add_tag_missing))
            return
        }
        val existing = app.db.checkpoints(tour.id).firstOrNull { normalizeName(it.name) == normalizeName(typed) }
        if (existing != null) {
            // Already on this tour, so there is nothing to create; go straight to programming it.
            pending = existing
            hideKeyboard()
            toast(getString(R.string.add_tag_exists, existing.name), long = true)
            render()
            return
        }
        if (busy) return
        busy = true
        program.isEnabled = false
        val current = tour
        Bg.run({ repo.createCheckpoint(current, typed) }) { result ->
            busy = false
            result.onSuccess { created ->
                pending = created
                hideKeyboard()
                render()
            }
            result.onFailure { failure ->
                program.isEnabled = true
                when (failure) {
                    is SupabaseClient.AuthException -> handleAuthExpired()
                    is SupabaseClient.ApiException -> toast(describe(failure), long = true)
                    is IOException -> toast(failure.message ?: getString(R.string.setup_offline), long = true)
                    else -> toast(describe(failure), long = true)
                }
            }
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(name.windowToken, 0)
    }

    private fun onTagDiscovered(tag: Tag) {
        val target = pending ?: return
        val uid = NfcTags.uidHex(tag)
        val previous = NfcTags.readName(tag)
        val written = NfcTags.writeName(tag, target.name)
        val replaced = previous?.takeIf { normalizeName(it) != normalizeName(target.name) }
        Bg.post { finishWrite(target, uid, written, replaced) }
    }

    private fun finishWrite(target: Checkpoint, uid: String, written: Boolean, replaced: String?) {
        if (!written) {
            Feedback.warning(this)
            toast(getString(R.string.setup_write_failed), long = true)
            return
        }
        Bg.run({ repo.recordTagWritten(target, uid) }) { result ->
            result.onSuccess {
                Feedback.success(this)
                val message = if (replaced != null) {
                    getString(R.string.setup_written_replaced, replaced, it.name)
                } else {
                    getString(R.string.setup_written, it.name)
                }
                toast(message, long = true)
                // Ready for the next checkpoint on the same tour.
                pending = null
                name.setText("")
                render()
            }
            result.onFailure { failure ->
                // The tag carries the name, so the only thing lost is the written-at stamp.
                Feedback.warning(this)
                toast(describe(failure), long = true)
                pending = null
                render()
            }
        }
    }

    companion object {
        private const val EXTRA_TOUR_ID = "tour_id"

        fun intent(context: Context, tourId: String): Intent =
            Intent(context, AddTagActivity::class.java).putExtra(EXTRA_TOUR_ID, tourId)
    }
}
