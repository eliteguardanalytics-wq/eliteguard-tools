package com.eliteguard.checkpoint.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toolbar
import com.eliteguard.checkpoint.App
import com.eliteguard.checkpoint.R
import com.eliteguard.checkpoint.data.Property
import com.eliteguard.checkpoint.net.SupabaseClient
import com.eliteguard.checkpoint.util.Bg

class SitesActivity : Activity() {

    private val app by lazy { App.get(this) }
    private lateinit var list: ListView
    private lateinit var message: TextView
    private lateinit var syncStatus: TextView
    private lateinit var activeTourBanner: TextView
    private val adapter = SiteAdapter()
    private var refreshing = false
    private var refreshedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sites)
        setupToolbar(findViewById<Toolbar>(R.id.toolbar), getString(R.string.sites_title), showUp = false)
        findViewById<TextView>(R.id.officer_name).text = app.session.officerName
        syncStatus = findViewById(R.id.sync_status)
        message = findViewById(R.id.message)
        activeTourBanner = findViewById(R.id.active_tour_banner)
        list = findViewById(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            startActivity(TourActivity.intent(this, adapter.getItem(position).id))
        }
    }

    override fun onResume() {
        super.onResume()
        loadLocal()
        if (!refreshedOnce) refresh()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_sites, menu)
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
        Bg.run({ Triple(db.properties(), db.checkpointCounts(), db.anyActiveLog()) to db.pendingCount() }) { result ->
            result.onSuccess { (data, pending) ->
                val (properties, counts, active) = data
                adapter.update(properties, counts)
                if (!refreshing) {
                    message.visibility = if (properties.isEmpty()) View.VISIBLE else View.GONE
                    if (properties.isEmpty()) message.text = getString(R.string.sites_empty)
                }
                if (active != null) {
                    activeTourBanner.text = getString(R.string.sites_active_tour, active.propertyName)
                    activeTourBanner.visibility = View.VISIBLE
                    activeTourBanner.setOnClickListener { startActivity(TourActivity.intent(this, active.propertyId)) }
                } else {
                    activeTourBanner.visibility = View.GONE
                }
                syncStatus.text = if (pending > 0) getString(R.string.sites_pending_sync, pending) else getString(R.string.sites_all_synced)
            }
        }
    }

    /** Uploads anything queued, then downloads the latest sites, checkpoints and routes. */
    private fun refresh() {
        if (refreshing) return
        refreshing = true
        refreshedOnce = true
        message.text = getString(R.string.sites_loading)
        message.visibility = View.VISIBLE
        val repo = app.repo
        Bg.run({
            repo.pushPending()
            repo.refreshReferenceData()
        }) { result ->
            refreshing = false
            result.onSuccess {
                message.visibility = View.GONE
                toast(getString(R.string.sites_refreshed))
            }
            result.onFailure { error ->
                if (error is SupabaseClient.AuthException) {
                    handleAuthExpired()
                    return@run
                }
                message.text = if (error is SupabaseClient.ApiException) describe(error) else getString(R.string.sites_offline)
                message.visibility = View.VISIBLE
            }
            loadLocal()
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

    private inner class SiteAdapter : BaseAdapter() {
        private var items: List<Property> = emptyList()
        private var counts: Map<String, Int> = emptyMap()

        fun update(properties: List<Property>, checkpointCounts: Map<String, Int>) {
            items = properties
            counts = checkpointCounts
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Property = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.row_site, parent, false)
            val property = items[position]
            view.findViewById<TextView>(R.id.name).text = property.name
            val address = view.findViewById<TextView>(R.id.address)
            val subtitle = listOfNotNull(property.address?.takeIf { it.isNotBlank() }, property.zone?.takeIf { it.isNotBlank() }).joinToString(" · ")
            address.text = subtitle
            address.visibility = if (subtitle.isEmpty()) View.GONE else View.VISIBLE
            view.findViewById<TextView>(R.id.count).text = getString(R.string.sites_checkpoint_count, counts[property.id] ?: 0)
            return view
        }
    }
}
