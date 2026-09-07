package com.eliteguard.checkpoint.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toolbar
import com.eliteguard.checkpoint.App
import com.eliteguard.checkpoint.R
import com.eliteguard.checkpoint.data.Property
import com.eliteguard.checkpoint.data.Repository
import com.eliteguard.checkpoint.data.Tour
import com.eliteguard.checkpoint.data.TourLog
import com.eliteguard.checkpoint.nfc.NfcTags
import com.eliteguard.checkpoint.util.Bg
import com.eliteguard.checkpoint.util.TimeFmt

/**
 * The officer's tour screen: the site's checkpoint list, a Start/End button, and NFC reader
 * mode that marks a checkpoint as scanned the moment its tag is tapped.
 */
class TourActivity : Activity() {

    /** One row in the checklist. */
    private data class Row(val checkpointId: String, val name: String, val scannedAt: String?)

    private val app by lazy { App.get(this) }
    private val repo by lazy { app.repo }

    private lateinit var property: Property
    private var tours: List<Tour> = emptyList()
    private var selectedTour: Tour? = null
    private var activeLog: TourLog? = null
    private var rows: List<Row> = emptyList()

    private lateinit var routeRow: View
    private lateinit var routeSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var nfcBanner: TextView
    private lateinit var hint: TextView
    private lateinit var startButton: Button
    private lateinit var endButton: Button
    private val adapter = CheckpointAdapter()

    private var nfc: NfcAdapter? = null
    private var lastTagUid: String? = null
    private var lastTagAt: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val propertyId = intent.getStringExtra(EXTRA_PROPERTY_ID)
        val loaded = propertyId?.let { app.db.property(it) }
        if (loaded == null) {
            finish()
            return
        }
        property = loaded
        setContentView(R.layout.activity_tour)
        setupToolbar(findViewById<Toolbar>(R.id.toolbar), property.name, showUp = true)

        routeRow = findViewById(R.id.route_row)
        routeSpinner = findViewById(R.id.route)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)
        nfcBanner = findViewById(R.id.nfc_banner)
        hint = findViewById(R.id.hint)
        startButton = findViewById(R.id.start)
        endButton = findViewById(R.id.end)
        findViewById<ListView>(R.id.list).adapter = adapter

        startButton.setOnClickListener { startTour() }
        endButton.setOnClickListener { confirmEndTour() }
        nfcBanner.setOnClickListener { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
        nfc = NfcAdapter.getDefaultAdapter(this)

        routeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (activeLog != null) return
                val chosen = if (position == 0) null else tours.getOrNull(position - 1)
                if (chosen?.id != selectedTour?.id) {
                    selectedTour = chosen
                    reload()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
        loadTours()
    }

    override fun onResume() {
        super.onResume()
        if (!::property.isInitialized) return
        reload()
        updateNfcBanner()
        nfc?.takeIf { it.isEnabled }?.enableReaderMode(this, ::onTagDiscovered, NfcTags.READER_FLAGS, null)
    }

    override fun onPause() {
        super.onPause()
        if (::property.isInitialized) nfc?.disableReaderMode(this)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (app.session.canManageCheckpoints) menuInflater.inflate(R.menu.menu_tour, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish(); true
        }
        R.id.action_setup -> {
            startActivity(SetupActivity.intent(this, property.id)); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // ------------------------------------------------------------------ state

    private fun loadTours() {
        val db = app.db
        Bg.run({ db.tours(property.id) }) { result ->
            result.onSuccess { list ->
                tours = list
                val names = listOf(getString(R.string.tour_all_checkpoints)) + list.map { it.name }
                val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
                spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                routeSpinner.adapter = spinnerAdapter
                routeRow.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                pinSpinnerToActiveLog()
            }
        }
    }

    /** While a tour is running the route picker shows that tour's route and is locked. */
    private fun pinSpinnerToActiveLog() {
        val log = activeLog ?: return
        if (routeSpinner.adapter == null) return
        val index = log.tourId?.let { id -> tours.indexOfFirst { it.id == id } } ?: -1
        val target = if (index >= 0) index + 1 else 0
        if (routeSpinner.selectedItemPosition != target) routeSpinner.setSelection(target)
    }

    /** Rebuilds the checklist from the database (active tour snapshot, or the live route). */
    private fun reload() {
        val db = app.db
        val tour = selectedTour
        Bg.run({
            val log = db.activeLog(property.id)
            val list = if (log != null) {
                val scans = db.scans(log.id).filter { !it.isDuplicate }.groupBy { it.checkpointId }
                db.logChecklist(log.id).map { Row(it.checkpointId, it.name, scans[it.checkpointId]?.minOf { s -> s.scannedAt }) }
            } else {
                repo.routeCheckpoints(property, tour).map { Row(it.id, it.name, null) }
            }
            log to list
        }) { result ->
            result.onSuccess { (log, list) ->
                activeLog = log
                rows = list
                adapter.notifyDataSetChanged()
                pinSpinnerToActiveLog()
                render()
            }
        }
    }

    private fun render() {
        val log = activeLog
        val scanned = rows.count { it.scannedAt != null }
        progress.max = rows.size.coerceAtLeast(1)
        progress.progress = scanned
        routeSpinner.isEnabled = log == null
        if (log == null) {
            status.text = getString(R.string.tour_not_started)
            hint.text = if (rows.isEmpty()) getString(R.string.tour_no_checkpoints) else getString(R.string.tour_ready_hint)
            hint.setBackgroundResource(R.drawable.bg_banner_warning)
            hint.setTextColor(getColor(R.color.warning))
            startButton.visibility = View.VISIBLE
            startButton.isEnabled = rows.isNotEmpty()
            endButton.visibility = View.GONE
        } else {
            status.text = getString(R.string.tour_in_progress, TimeFmt.time(log.startedAt), scanned, rows.size)
            val done = scanned >= rows.size
            hint.text = if (done) getString(R.string.tour_complete_banner) else getString(R.string.tour_ready_hint)
            hint.setBackgroundResource(if (done) R.drawable.bg_banner_success else R.drawable.bg_banner_warning)
            hint.setTextColor(getColor(if (done) R.color.success else R.color.warning))
            startButton.visibility = View.GONE
            endButton.visibility = View.VISIBLE
        }
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

    // ------------------------------------------------------------------ actions

    private fun startTour() {
        if (rows.isEmpty()) {
            toast(getString(R.string.tour_no_checkpoints))
            return
        }
        startButton.isEnabled = false
        val tour = selectedTour
        Bg.run({ repo.startTour(property, tour) }) { result ->
            startButton.isEnabled = true
            result.onSuccess { reload() }
            result.onFailure { toast(describe(it), long = true) }
        }
    }

    private fun confirmEndTour() {
        val log = activeLog ?: return
        val missed = rows.filter { it.scannedAt == null }
        val message = if (missed.isEmpty()) {
            getString(R.string.tour_end_all_done, rows.size)
        } else {
            getString(R.string.tour_end_missing, rows.size - missed.size, rows.size, missed.joinToString("\n") { "• ${it.name}" })
        }
        confirm(getString(R.string.tour_end_title), message, getString(R.string.tour_end_confirm)) {
            Bg.run({ repo.endTour(log) to app.db.pendingCount() }) { result ->
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
        val ndefId = NfcTags.readCheckpointId(tag)
        Bg.post { handleTag(uid, ndefId) }
    }

    private fun handleTag(uid: String, ndefCheckpointId: String?) {
        val now = System.currentTimeMillis()
        if (uid == lastTagUid && now - lastTagAt < TAP_DEBOUNCE_MS) return
        lastTagUid = uid
        lastTagAt = now

        val log = activeLog
        if (log == null) {
            Feedback.warning(this)
            toast(getString(R.string.tour_scan_not_started), long = true)
            return
        }
        Bg.run({
            val checkpoint = repo.resolveTag(uid, ndefCheckpointId)?.takeIf { it.propertyId == property.id }
            if (checkpoint == null) null else checkpoint to repo.recordScan(log, checkpoint, uid)
        }) { result ->
            result.onSuccess { outcome ->
                if (outcome == null) {
                    Feedback.warning(this)
                    toast(getString(R.string.tour_scan_unknown), long = true)
                    return@run
                }
                val (checkpoint, scan) = outcome
                when (scan.first) {
                    Repository.ScanOutcome.SCANNED -> {
                        Feedback.success(this)
                        toast(getString(R.string.tour_scan_ok, checkpoint.name))
                    }
                    Repository.ScanOutcome.DUPLICATE -> {
                        Feedback.warning(this)
                        val at = rows.firstOrNull { it.checkpointId == checkpoint.id }?.scannedAt
                        toast(getString(R.string.tour_scan_duplicate, checkpoint.name, TimeFmt.time(at)), long = true)
                    }
                    Repository.ScanOutcome.NOT_IN_ROUTE -> {
                        Feedback.warning(this)
                        toast(getString(R.string.tour_scan_not_in_route, checkpoint.name), long = true)
                    }
                }
                reload()
            }
            result.onFailure { toast(describe(it), long = true) }
        }
    }

    // ------------------------------------------------------------------ list

    private inner class CheckpointAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Row = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.row_checkpoint, parent, false)
            val row = rows[position]
            val scanned = row.scannedAt != null
            view.findViewById<View>(R.id.card).setBackgroundResource(if (scanned) R.drawable.bg_card_scanned else R.drawable.bg_card)
            view.findViewById<TextView>(R.id.step).apply {
                text = (position + 1).toString()
                visibility = if (scanned) View.GONE else View.VISIBLE
            }
            view.findViewById<ImageView>(R.id.check).visibility = if (scanned) View.VISIBLE else View.GONE
            view.findViewById<TextView>(R.id.name).text = row.name
            val detail = view.findViewById<TextView>(R.id.detail)
            detail.text = if (scanned) getString(R.string.tour_scanned_at, TimeFmt.time(row.scannedAt)) else getString(R.string.tour_pending)
            detail.setTextColor(getColor(if (scanned) R.color.success else R.color.text_secondary))
            return view
        }
    }

    companion object {
        private const val EXTRA_PROPERTY_ID = "property_id"
        private const val TAP_DEBOUNCE_MS = 1500L

        fun intent(context: Context, propertyId: String): Intent =
            Intent(context, TourActivity::class.java).putExtra(EXTRA_PROPERTY_ID, propertyId)
    }
}
