package com.eliteguard.checkpoint.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import android.widget.Toolbar
import com.eliteguard.checkpoint.App
import com.eliteguard.checkpoint.R
import com.eliteguard.checkpoint.data.Repository
import com.eliteguard.checkpoint.net.SupabaseClient
import java.io.IOException

fun Context.toast(message: CharSequence, long: Boolean = false) {
    Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

/** Turns an exception into a message an officer can act on. */
fun Context.describe(error: Throwable): String = when (error) {
    is SupabaseClient.InvalidCredentials -> getString(R.string.login_invalid)
    is Repository.ProfileMissing -> getString(R.string.login_no_profile)
    is SupabaseClient.AuthException -> getString(R.string.session_expired)
    is SupabaseClient.ApiException -> getString(R.string.error_generic, error.message ?: "HTTP ${error.status}")
    is IOException -> getString(R.string.login_network)
    else -> getString(R.string.error_generic, error.message ?: error.javaClass.simpleName)
}

/** Drops the dead session and returns to the sign-in screen. */
fun Activity.handleAuthExpired() {
    App.get(this).session.clear()
    toast(getString(R.string.session_expired), long = true)
    val intent = Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    startActivity(intent)
    finish()
}

fun Activity.setupToolbar(toolbar: Toolbar, title: CharSequence, showUp: Boolean) {
    setActionBar(toolbar)
    actionBar?.title = title
    actionBar?.setDisplayHomeAsUpEnabled(showUp)
}

fun Activity.confirm(title: CharSequence, message: CharSequence, positive: CharSequence, onConfirm: () -> Unit) {
    AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(positive) { _, _ -> onConfirm() }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

/** Haptic and audible feedback for tag taps so an officer never has to look at the screen. */
object Feedback {
    fun success(context: Context) {
        vibrate(context, longArrayOf(0, 60))
        tone(ToneGenerator.TONE_PROP_ACK, 160)
    }

    fun warning(context: Context) {
        vibrate(context, longArrayOf(0, 60, 80, 60))
        tone(ToneGenerator.TONE_PROP_NACK, 260)
    }

    private fun vibrate(context: Context, pattern: LongArray) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        try {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (e: Exception) {
            // Some devices have no vibrator; feedback is optional.
        }
    }

    private fun tone(tone: Int, durationMs: Int) {
        try {
            val generator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            generator.startTone(tone, durationMs)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ generator.release() }, durationMs + 100L)
        } catch (e: Exception) {
            // Audio may be unavailable; ignore.
        }
    }
}
