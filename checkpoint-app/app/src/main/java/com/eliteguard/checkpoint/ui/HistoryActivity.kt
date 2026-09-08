package com.eliteguard.checkpoint.ui

import android.app.Activity
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
import com.eliteguard.checkpoint.data.LogStatus
import com.eliteguard.checkpoint.data.TourLog
import com.eliteguard.checkpoint.util.Bg
import com.eliteguard.checkpoint.util.TimeFmt

/** Tours recorded on this phone, newest first. The web portal will hold the full history. */
class HistoryActivity : Activity() {

    private val adapter = HistoryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)
        setupToolbar(findViewById<Toolbar>(R.id.toolbar), getString(R.string.history_title), showUp = true)
        findViewById<ListView>(R.id.list).adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        val db = App.get(this).db
        Bg.run({ db.logs() }) { result ->
            result.onSuccess { logs ->
                adapter.update(logs)
                findViewById<TextView>(R.id.empty).visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private inner class HistoryAdapter : BaseAdapter() {
        private var items: List<TourLog> = emptyList()

        fun update(logs: List<TourLog>) {
            items = logs
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): TourLog = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.row_history, parent, false)
            val log = items[position]
            val title = if (log.tourName != null) "${log.propertyName} · ${log.tourName}" else log.propertyName
            view.findViewById<TextView>(R.id.site).text = title
            val status = view.findViewById<TextView>(R.id.status)
            when (log.status) {
                LogStatus.IN_PROGRESS -> {
                    status.text = getString(R.string.history_status_in_progress); status.setTextColor(getColor(R.color.warning))
                }
                LogStatus.COMPLETED -> {
                    status.text = getString(R.string.history_status_completed); status.setTextColor(getColor(R.color.success))
                }
                else -> {
                    status.text = getString(R.string.history_status_incomplete); status.setTextColor(getColor(R.color.error))
                }
            }
            view.findViewById<TextView>(R.id.line).text =
                getString(R.string.history_line, TimeFmt.dateTime(log.startedAt), log.scannedCheckpoints, log.totalCheckpoints)
            val sync = view.findViewById<TextView>(R.id.sync)
            sync.text = if (log.synced) getString(R.string.history_uploaded) else getString(R.string.history_not_uploaded)
            sync.setTextColor(getColor(if (log.synced) R.color.success else R.color.warning))
            return view
        }
    }
}
