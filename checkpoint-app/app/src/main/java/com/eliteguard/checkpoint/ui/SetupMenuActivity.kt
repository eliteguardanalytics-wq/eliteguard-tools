package com.eliteguard.checkpoint.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toolbar
import com.eliteguard.checkpoint.App
import com.eliteguard.checkpoint.R
import com.eliteguard.checkpoint.net.SupabaseClient
import com.eliteguard.checkpoint.util.Bg
import java.io.IOException

/**
 * What an admin can do on site: add a tour, program a tag for a checkpoint that already exists,
 * or add a brand new checkpoint and program its tag in one go.
 */
class SetupMenuActivity : Activity() {

    private val app by lazy { App.get(this) }
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!app.session.canManageCheckpoints || !app.device.isEnrolled) {
            finish()
            return
        }
        setContentView(R.layout.activity_setup_menu)
        setupToolbar(findViewById<Toolbar>(R.id.toolbar), getString(R.string.setup_title), showUp = true)
        actionBar?.subtitle = app.device.siteName

        findViewById<Button>(R.id.create_tour).setOnClickListener { promptTourName() }
        findViewById<Button>(R.id.program_existing).setOnClickListener {
            startActivity(TourPickerActivity.intent(this, TourPickerActivity.Mode.PROGRAM_EXISTING))
        }
        findViewById<Button>(R.id.add_new).setOnClickListener {
            startActivity(TourPickerActivity.intent(this, TourPickerActivity.Mode.ADD_NEW))
        }
    }

    override fun onResume() {
        super.onResume()
        if (!app.device.isEnrolled) return
        val db = app.db
        Bg.run({ db.tours().size to db.checkpointCounts().values.sum() }) { result ->
            result.onSuccess { (tours, checkpoints) ->
                findViewById<TextView>(R.id.summary).text =
                    getString(R.string.setup_menu_summary, tours, checkpoints)
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

    private fun promptTourName() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_single_name, null)
        val input = view.findViewById<EditText>(R.id.name)
        view.findViewById<TextView>(R.id.hint).text = getString(R.string.tour_new_hint, app.device.siteName)
        AlertDialog.Builder(this)
            .setTitle(R.string.tour_new_title)
            .setView(view)
            .setPositiveButton(R.string.tour_new_save) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.tour_new_missing))
                    return@setPositiveButton
                }
                if (busy) return@setPositiveButton
                busy = true
                val repo = app.repo
                Bg.run({ repo.createTour(name) }) { result ->
                    busy = false
                    result.onSuccess { toast(getString(R.string.tour_new_done, it.name), long = true) }
                    result.onFailure { failure ->
                        when (failure) {
                            is SupabaseClient.AuthException -> handleAuthExpired()
                            is SupabaseClient.ApiException -> toast(describe(failure), long = true)
                            is IOException -> toast(getString(R.string.setup_offline), long = true)
                            else -> toast(describe(failure), long = true)
                        }
                    }
                    onResume()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
