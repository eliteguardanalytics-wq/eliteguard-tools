package com.eliteguard.checkpoint.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toolbar
import com.eliteguard.checkpoint.App
import com.eliteguard.checkpoint.R
import com.eliteguard.checkpoint.data.ChecklistRow
import com.eliteguard.checkpoint.data.Repository
import com.eliteguard.checkpoint.data.Tour
import com.eliteguard.checkpoint.data.TourLog
import com.eliteguard.checkpoint.nfc.NfcTags
import com.eliteguard.checkpoint.util.Bg
import com.eliteguard.checkpoint.util.TimeFmt

/**
 * The officer's tour: this tour's checkpoint names, and NFC reader mode. Tapping a tag matches the
 * name written on it against the list; a match turns green and drops to the bottom, leaving what
 * is still outstanding at the top. End Tour finishes whether or not everything was scanned.
 */
class TourActivity : Activity() {

    private val app by lazy { App.get(this) }
    private val repo by lazy { app.repo }

    private lateinit var tour: Tour
    private var log: TourLog? = null
    private var rows: List<ChecklistRow> = emptyList()

    private lateinit var status: TextView
    private lateinit var started: TextView
    private lateinit var progress: ProgressBar
    private lateinit var nfcBanner: TextView
    private lateinit var hint: TextView
    private lateinit var endButton: Button
    private val adapter = ChecklistAdapter()

    private var nfc: NfcAdapter? = null
    private var lastTagUid: String? = null
    private var lastTagAt: Long = 0L
    private var ending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val loaded = intent.getStringExtra(EXTRA_TOUR_ID)?.let { app.db.tour(it) }
        if (loaded == null) {
            finish()
            return
        }
        tour = loaded
        setContentView(R.layout.activity_tour)
        setupToolbar(findViewById<Toolbar>(R.id.toolbar), tour.name, showUp = true)
        actionBar?.subtitle = tour.propertyName

        status = findViewById(R.id.status)
        started = findViewById(R.id.started)
        progress = findViewById(R.id.progress)
        nfcBanner = findViewById(R.id.nfc_banner)
        hint = findViewById(R.id.hint)
        endButton = findViewById(R.id.end)
        findViewById<ListView>(R.id.list).adapter = adapter

        endButton.setOnClickListener { confirmEndTour() }
        nfcBanner.setOnClickListener { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
        nfc = NfcAdapter.getDefaultAdapter(this)

        // Selecting a tour starts it; an unfinished one is picked back up where it left off.
        beginOrResume()
    }

    override fun onResume() {
        super.onResume()
        if (!::tour.isInitialized) return
        reload()
        updateNfcBanner()
        nfc?.takeIf { it.isEnabled }?.enableReaderMode(this, ::onTagDiscovered, NfcTags.READER_FLAGS, null)
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

    // ------------------------------------------------------------------ state

    private fun beginOrResume() {
        val db = app.db
        val current = tour
        Bg.run({ db.activeLogForTour(current.id) ?: repo.startTour(current) }) { result ->
            result.onSuccess {
                log = it
                reload()
            }
            result.onFailure { toast(describe(it), long = true) }
        }
    }

    private fun reload() {
        val db = app.db
        val current = log ?: return
        Bg.run({ db.log(current.id) to db.checklist(current.id) }) { result ->
            result.onSuccess { (refreshed, checklist) ->
                if (refreshed != null) log = refreshed
                rows = checklist
                adapter.notifyDataSetChanged()
                render()
            }
        }
    }

    private fun render() {
        val current = log ?: return
        val scanned = rows.count { it.isScanned }
        progress.max = rows.size.coerceAtLeast(1)
        progress.progress = scanned
        status.text = getString(R.string.tour_scanned_count, scanned, rows.size)
        started.text = getString(R.string.tour_started_at, TimeFmt.time(current.startedAt))
        val done = rows.isNotEmpty() && scanned >= rows.size
        hint.text = when {
            rows.isEmpty() -> getString(R.string.tour_no_checkpoints)
            done -> getString(R.string.tour_complete_banner)
            else -> getString(R.string.tour_hint)
        }
        hint.setBackgroundResource(if (done) R.drawable.bg_banner_success else R.drawable.bg_banner_warning)
        hint.setTextColor(getColor(if (done) R.color.success else R.color.warning))
    }

    private fun updateNfcBanner() {
        val adapter = nfc
        when {
            adapter == null -> {
                nfcBanner.text = getString(R.string.nfc_missing); nfcBanner.visibility = View.VISIBLE
            }
            !adapter.isEnabled -> {
                nfcBanner.text = getString(R.string.nfc_disabled) + "\n" + getString(R.string.nfc_open_settings)
                nfcBanner.visibility = View.VISIBLE
            }
            else -> nfcBanner.visibility = View.GONE
        }
    }

    // ------------------------------------------------------------------ ending

    private fun confirmEndTour() {
        val current = log ?: return
        if (ending) return
        val missed = rows.filter { !it.isScanned }
        val message = if (missed.isEmpty()) {
            getString(R.string.tour_end_all_done, rows.size)
        } else {
            getString(R.string.tour_end_missing, rows.size - missed.size, rows.size, missed.joinToString("\n") { "• ${it.name}" })
        }
        confirm(getString(R.string.tour_end_title), message, getString(R.string.tour_end_confirm)) {
            ending = true
            Bg.run({ repo.endTour(current) to app.db.pendingCount() }) { result ->
                ending = false
                result.onSuccess { (ended, pending) ->
                    toast(getString(R.string.tour_ended, ended.scannedCheckpoints, ended.totalCheckpoints), long = true)
                    if (pending > 0) toast(getString(R.string.tour_sync_later))
                    finish()
                }
                result.onFailure { toast(describe(it), long = true) }
            }
        }
    }

    // ------------------------------------------------------------------ NFC

    /** Called by the NFC service on a background thread whenever a tag comes into range. */
    private fun onTagDiscovered(tag: Tag) {
        val uid = NfcTags.uidHex(tag)
        val name = NfcTags.readName(tag)
        Bg.post { handleTag(uid, name) }
    }

    private fun handleTag(uid: String, tagName: String?) {
        val now = System.currentTimeMillis()
        if (uid == lastTagUid && now - lastTagAt < TAP_DEBOUNCE_MS) return
        lastTagUid = uid
        lastTagAt = now

        val current = log ?: return
        // Captured before the write so a duplicate can report the original scan time.
        val previousScanAt = rows.associate { it.name to it.scannedAt }
        Bg.run({ repo.recordScan(current, tagName, uid) }) { result ->
            result.onSuccess { scan ->
                log = scan.log
                when (scan.outcome) {
                    Repository.ScanOutcome.SCANNED -> {
                        Feedback.success(this)
                        toast(getString(R.string.tour_scan_ok, scan.checkpointName))
                    }
                    Repository.ScanOutcome.DUPLICATE -> {
                        Feedback.warning(this)
                        val at = previousScanAt[scan.checkpointName]
                        toast(getString(R.string.tour_scan_duplicate, scan.checkpointName, TimeFmt.time(at)), long = true)
                    }
                    Repository.ScanOutcome.NOT_ON_TOUR -> {
                        Feedback.warning(this)
                        toast(getString(R.string.tour_scan_not_on_tour, scan.checkpointName), long = true)
                    }
                    Repository.ScanOutcome.BLANK -> {
                        Feedback.warning(this)
                        toast(getString(R.string.tour_scan_blank), long = true)
                    }
                }
                reload()
            }
            result.onFailure { toast(describe(it), long = true) }
        }
    }

    // ------------------------------------------------------------------ list

    private inner class ChecklistAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): ChecklistRow = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()
        override fun areAllItemsEnabled(): Boolean = false
        override fun isEnabled(position: Int): Boolean = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.row_checkpoint, parent, false)
            val row = rows[position]
            view.findViewById<View>(R.id.card)
                .setBackgroundResource(if (row.isScanned) R.drawable.bg_card_scanned else R.drawable.bg_card)
            view.findViewById<TextView>(R.id.step).apply {
                text = (position + 1).toString()
                visibility = if (row.isScanned) View.GONE else View.VISIBLE
            }
            view.findViewById<ImageView>(R.id.check).visibility = if (row.isScanned) View.VISIBLE else View.GONE
            view.findViewById<TextView>(R.id.name).text = row.name
            val detail = view.findViewById<TextView>(R.id.detail)
            detail.text = if (row.isScanned) {
                getString(R.string.tour_scanned_at, TimeFmt.time(row.scannedAt))
            } else {
                getString(R.string.tour_pending)
            }
            detail.setTextColor(getColor(if (row.isScanned) R.color.success else R.color.text_secondary))
            return view
        }
    }

    companion object {
        private const val EXTRA_TOUR_ID = "tour_id"
        private const val TAP_DEBOUNCE_MS = 1500L

        fun intent(context: Context, tourId: String): Intent =
            Intent(context, TourActivity::class.java).putExtra(EXTRA_TOUR_ID, tourId)
    }
}
