package com.eliteguard.checkpoint.ui

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toolbar
import com.eliteguard.checkpoint.App
import com.eliteguard.checkpoint.R
import com.eliteguard.checkpoint.net.SupabaseClient
import com.eliteguard.checkpoint.util.Bg

/**
 * What an officer sees after signing in: one Start Tour button, and for administrators a second
 * button for writing checkpoint names onto tags. An unfinished tour offers to resume instead.
 */
class HomeActivity : Activity() {

    private val app by lazy { App.get(this) }
    private lateinit var startTour: Button
    private lateinit var setupTags: Button
    private lateinit var syncStatus: TextView
    private lateinit var activeTourBanner: TextView
    private lateinit var message: TextView
    private lateinit var nfcBanner: TextView
    private var refreshing = false
    private var refreshedOnce = false
    private var activeTourId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        setupToolbar(findViewById<Toolbar>(R.id.toolbar), getString(R.string.home_title), showUp = false)

        findViewById<TextView>(R.id.officer_name).text = getString(R.string.home_greeting, app.session.officerName)
        findViewById<TextView>(R.id.officer_role).apply {
            visibility = if (app.session.canManageCheckpoints) View.VISIBLE else View.GONE
            text = getString(R.string.home_admin)
        }
        syncStatus = findViewById(R.id.sync_status)
        activeTourBanner = findViewById(R.id.active_tour_banner)
        message = findViewById(R.id.message)
        nfcBanner = findViewById(R.id.nfc_banner)
        startTour = findViewById(R.id.start_tour)
        setupTags = findViewById(R.id.setup_tags)

        startTour.setOnClickListener {
            val running = activeTourId
            if (running != null) {
                startActivity(TourActivity.intent(this, running))
            } else {
                startActivity(TourPickerActivity.intent(this, forSetup = false))
            }
        }
        setupTags.setOnClickListener { startActivity(TourPickerActivity.intent(this, forSetup = true)) }
        setupTags.visibility = if (app.session.canManageCheckpoints) View.VISIBLE else View.GONE
        nfcBanner.setOnClickListener { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }
    }

    override fun onResume() {
        super.onResume()
        loadLocal()
        showNfcState()
        if (!refreshedOnce) refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_home, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_refresh -> {
            refresh(); true
        }
        R.id.action_history -> {
            startActivity(Intent(this, HistoryActivity::class.java)); true
        }
        R.id.action_logout -> {
            confirmLogout(); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /** Shows whatever is cached on the phone; works fully offline. */
    private fun loadLocal() {
        val db = app.db
        Bg.run({ Triple(db.anyActiveLog(), db.pendingCount(), db.tours().size) }) { result ->
            result.onSuccess { (active, pending, tourCount) ->
                activeTourId = active?.tourId
                if (active != null) {
                    activeTourBanner.text = getString(R.string.home_active_tour, active.propertyName, active.tourName)
                    activeTourBanner.visibility = View.VISIBLE
                    startTour.text = getString(R.string.home_resume_tour)
                } else {
                    activeTourBanner.visibility = View.GONE
                    startTour.text = getString(R.string.home_start_tour)
                }
                startTour.isEnabled = tourCount > 0 || active != null
                syncStatus.text = if (pending > 0) getString(R.string.home_pending_sync, pending) else getString(R.string.home_all_synced)
                if (!refreshing && tourCount == 0) {
                    message.text = getString(R.string.home_no_tours)
                    message.visibility = View.VISIBLE
                } else if (!refreshing) {
                    message.visibility = View.GONE
                }
            }
        }
    }

    /** Uploads anything queued, then downloads the latest sites, tours and checkpoints. */
    private fun refresh() {
        if (refreshing) return
        refreshing = true
        refreshedOnce = true
        message.text = getString(R.string.home_refreshing)
        message.visibility = View.VISIBLE
        val repo = app.repo
        Bg.run({
            repo.pushPending()
            repo.refreshReferenceData()
        }) { result ->
            refreshing = false
            result.onSuccess {
                message.visibility = View.GONE
                toast(getString(R.string.home_refreshed))
            }
            result.onFailure { error ->
                if (error is SupabaseClient.AuthException) {
                    handleAuthExpired()
                    return@run
                }
                message.text = if (error is SupabaseClient.ApiException) describe(error) else getString(R.string.home_offline)
                message.visibility = View.VISIBLE
            }
            loadLocal()
        }
    }

    private fun showNfcState() {
        val adapter = NfcAdapter.getDefaultAdapter(this)
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

    private fun confirmLogout() {
        val pending = app.db.pendingCount()
        val body = if (pending > 0) getString(R.string.logout_confirm_pending, pending) else ""
        confirm(getString(R.string.logout_confirm_title), body, getString(R.string.logout_confirm)) {
            val repo = app.repo
            Bg.run({ repo.signOut() }) {
                startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
        }
    }
}
