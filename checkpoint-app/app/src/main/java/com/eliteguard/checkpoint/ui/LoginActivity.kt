package com.eliteguard.checkpoint.ui

import android.app.Activity
import android.content.Intent
import android.nfc.NfcAdapter
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.eliteguard.checkpoint.App
import com.eliteguard.checkpoint.Config
import com.eliteguard.checkpoint.R
import com.eliteguard.checkpoint.util.Bg

class LoginActivity : Activity() {

    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var error: TextView
    private lateinit var signIn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (App.get(this).session.isLoggedIn) {
            openApp()
            return
        }
        setContentView(R.layout.activity_login)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        error = findViewById(R.id.error)
        signIn = findViewById(R.id.sign_in)
        findViewById<TextView>(R.id.version).text = "v${Config.VERSION_NAME}"

        signIn.setOnClickListener { attemptSignIn() }
        password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptSignIn()
                true
            } else {
                false
            }
        }
    }

    private fun attemptSignIn() {
        val user = username.text.toString().trim()
        val pass = password.text.toString()
        if (user.isEmpty() || pass.isEmpty()) {
            showError(getString(R.string.login_missing))
            return
        }
        error.visibility = View.GONE
        signIn.isEnabled = false
        signIn.text = getString(R.string.login_signing_in)
        val repo = App.get(this).repo
        Bg.run({ repo.signIn(user, pass) }) { result ->
            signIn.isEnabled = true
            signIn.text = getString(R.string.login_button)
            result.onSuccess { openApp() }
            result.onFailure { showError(describe(it)) }
        }
    }

    private fun showError(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }

    /** Goes to the site list; if launched by tapping a tag mid-tour, lands on the active tour. */
    private fun openApp() {
        startActivity(Intent(this, SitesActivity::class.java))
        if (intent?.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
            App.get(this).db.anyActiveLog()?.let { startActivity(TourActivity.intent(this, it.propertyId)) }
        }
        finish()
    }
}
