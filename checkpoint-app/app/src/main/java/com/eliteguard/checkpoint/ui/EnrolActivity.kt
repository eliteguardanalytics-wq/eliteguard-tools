package com.eliteguard.checkpoint.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.eliteguard.checkpoint.App
import com.eliteguard.checkpoint.Config
import com.eliteguard.checkpoint.R
import com.eliteguard.checkpoint.data.Repository
import com.eliteguard.checkpoint.util.Bg

/**
 * First run only. The installer types the portal host and the site license issued for it, and
 * the phone is tied to that site from then on. Signing out does not undo it; only a deliberate
 * reset from the home screen does.
 */
class EnrolActivity : Activity() {

    private lateinit var host: EditText
    private lateinit var license: EditText
    private lateinit var error: TextView
    private lateinit var activate: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (App.get(this).device.isEnrolled) {
            proceed()
            return
        }
        setContentView(R.layout.activity_enrol)
        findViewById<android.widget.ScrollView>(R.id.scroll).let { it.keepFocusedFieldVisible(); applyWindowInsets(top = it, bottom = it) }
        host = findViewById(R.id.host)
        license = findViewById(R.id.license)
        error = findViewById(R.id.error)
        activate = findViewById(R.id.activate)
        host.setText(Config.DEFAULT_PORTAL_HOST)
        findViewById<TextView>(R.id.version).text = "v${Config.VERSION_NAME}"
        activate.setOnClickListener { attempt() }
        license.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attempt(); true
            } else {
                false
            }
        }
    }

    private fun attempt() {
        val typedHost = host.text.toString()
        val typedLicense = license.text.toString()
        if (typedHost.isBlank() || typedLicense.isBlank()) {
            showError(getString(R.string.enrol_missing))
            return
        }
        error.visibility = View.GONE
        activate.isEnabled = false
        activate.text = getString(R.string.enrol_checking)
        val repo = App.get(this).repo
        Bg.run({ repo.enrol(typedHost, typedLicense) }) { result ->
            activate.isEnabled = true
            activate.text = getString(R.string.enrol_activate)
            result.onSuccess { site ->
                toast(getString(R.string.enrol_done, site.propertyName), long = true)
                proceed()
            }
            result.onFailure { failure ->
                showError(
                    if (failure is Repository.LicenseRejected) getString(R.string.enrol_rejected) else describe(failure)
                )
            }
        }
    }

    private fun showError(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }

    private fun proceed() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }
}
