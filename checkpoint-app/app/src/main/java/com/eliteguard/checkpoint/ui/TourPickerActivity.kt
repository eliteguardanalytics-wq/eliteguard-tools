package com.eliteguard.checkpoint.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toolbar
import com.eliteguard.checkpoint.App
import com.eliteguard.checkpoint.R
import com.eliteguard.checkpoint.data.Tour
import com.eliteguard.checkpoint.util.Bg

/**
 * The list of tours, grouped under their site. Used both by an officer about to walk a tour and
 * by an administrator about to write that tour's tags.
 */
class TourPickerActivity : Activity() {

    /** A row is either a site heading or a tour under it. */
    private sealed class Row {
        data class SiteHeader(val name: String) : Row()
        data class TourRow(val tour: Tour, val checkpointCount: Int, val inProgress: Boolean) : Row()
    }

    private val app by lazy { App.get(this) }
    private var forSetup = false
    private var rows: List<Row> = emptyList()
    private val adapter = TourAdapter()
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forSetup = intent.getBooleanExtra(EXTRA_FOR_SETUP, false)
        if (forSetup && !app.session.canManageCheckpoints) {
            finish()
            return
        }
        setContentView(R.layout.activity_tour_picker)
        val title = if (forSetup) R.string.picker_setup_title else R.string.picker_run_title
        setupToolbar(findViewById<Toolbar>(R.id.toolbar), getString(title), showUp = true)
        findViewById<TextView>(R.id.hint).setText(if (forSetup) R.string.picker_setup_hint else R.string.picker_run_hint)
        empty = findViewById(R.id.empty)
        val list = findViewById<ListView>(R.id.list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val row = rows.getOrNull(position) as? Row.TourRow ?: return@setOnItemClickListener
            if (forSetup) {
                startActivity(SetupActivity.intent(this, row.tour.id))
            } else {
                startActivity(TourActivity.intent(this, row.tour.id))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        reload()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun reload() {
        val db = app.db
        Bg.run({
            val tours = db.tours()
            val counts = db.checkpointCounts()
            val running = tours.filter { db.activeLogForTour(it.id) != null }.map { it.id }.toSet()
            val out = ArrayList<Row>()
            var currentSite: String? = null
            for (tour in tours) {
                if (tour.propertyName != currentSite) {
                    currentSite = tour.propertyName
                    out.add(Row.SiteHeader(tour.propertyName))
                }
                out.add(Row.TourRow(tour, counts[tour.id] ?: 0, tour.id in running))
            }
            out
        }) { result ->
            result.onSuccess {
                rows = it
                adapter.notifyDataSetChanged()
                empty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private inner class TourAdapter : BaseAdapter() {
        override fun getCount(): Int = rows.size
        override fun getItem(position: Int): Any = rows[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 2
        override fun getItemViewType(position: Int): Int = if (rows[position] is Row.SiteHeader) 0 else 1

        /** Site headings are labels, not choices, so they must not be tappable. */
        override fun isEnabled(position: Int): Boolean = rows[position] is Row.TourRow

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val inflater = LayoutInflater.from(parent.context)
            return when (val row = rows[position]) {
                is Row.SiteHeader -> {
                    val view = convertView ?: inflater.inflate(R.layout.row_site_header, parent, false)
                    (view as TextView).text = row.name
                    view
                }
                is Row.TourRow -> {
                    val view = convertView ?: inflater.inflate(R.layout.row_tour, parent, false)
                    view.findViewById<TextView>(R.id.name).text = row.tour.name
                    view.findViewById<TextView>(R.id.detail).text =
                        getString(R.string.picker_checkpoint_count, row.checkpointCount)
                    view.findViewById<TextView>(R.id.badge).apply {
                        visibility = if (row.inProgress) View.VISIBLE else View.GONE
                        text = getString(R.string.picker_in_progress)
                    }
                    view
                }
            }
        }
    }

    companion object {
        private const val EXTRA_FOR_SETUP = "for_setup"

        fun intent(context: Context, forSetup: Boolean): Intent =
            Intent(context, TourPickerActivity::class.java).putExtra(EXTRA_FOR_SETUP, forSetup)
    }
}
