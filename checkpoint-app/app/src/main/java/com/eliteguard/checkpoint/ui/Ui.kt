package com.eliteguard.checkpoint.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.View
import android.view.WindowInsets
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.ScrollView
import android.widget.Toast
import android.widget.Toolbar
import com.eliteguard.checkpoint.App
import com.eliteguard.checkpoint.R
import com.eliteguard.checkpoint.data.Repository
import com.eliteguard.checkpoint.net.SupabaseClient
import java.io.IOException

/**
 * Lays a screen out around the status bar, the navigation bar and the keyboard.
 *
 * From Android 15 an app targeting API 35 or higher draws behind the system bars, and Android 16
 * removed the opt-out for anything targeting API 36. `adjustResize` therefore does nothing: the
 * window never shrinks, so a toolbar sits under the clock and a field near the bottom sits under
 * the keyboard. The sizes arrive as window insets instead, and this applies them as padding.
 *
 * [top] takes the status bar height and should be a view already painted the right colour, so it
 * simply grows upward into that space. [bottom] takes whichever is taller of the navigation bar
 * and the keyboard, so the last thing on screen stays clear of both.
 *
 * Below API 35 the system still insets the window itself, and padding again would double it.
 */
fun Activity.applyWindowInsets(top: View? = null, bottom: View? = null) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    // Captured once: the listener runs again on every keyboard or rotation change, and reading
    // the current padding each time would add the inset on top of the inset already applied.
    val topPadding = top?.paddingTop ?: 0
    val bottomPadding = bottom?.paddingBottom ?: 0
    val host = findViewById<View>(android.R.id.content)
    host.setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        val keyboard = insets.getInsets(WindowInsets.Type.ime()).bottom
        top?.let { it.setPadding(it.paddingLeft, topPadding + bars.top, it.paddingRight, it.paddingBottom) }
        bottom?.let {
            it.setPadding(it.paddingLeft, it.paddingTop, it.paddingRight, bottomPadding + maxOf(bars.bottom, keyboard))
        }
        if (keyboard > 0) {
            val focused = currentFocus
            if (focused != null) {
                view.post { focused.requestRectangleOnScreen(Rect(0, 0, focused.width, focused.height), false) }
            }
        }
        insets
    }
    host.requestApplyInsets()
}

/**
 * Scrolls whatever has focus back into view when the keyboard takes space away.
 *
 * The keyboard opening makes the window shorter, which lays this out shorter. Reacting to the
 * new size, rather than guessing at a delay after a field is tapped, means the scroll happens
 * exactly once the size is actually known.
 */
fun ScrollView.keepFocusedFieldVisible() {
    addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
        if (bottom - top >= oldBottom - oldTop) return@addOnLayoutChangeListener
        val focused = findFocus() ?: return@addOnLayoutChangeListener
        post { focused.requestRectangleOnScreen(Rect(0, 0, focused.width, focused.height), false) }
    }
}

fun Context.toast(message: CharSequence, long: Boolean = false) {
    Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

/** Turns an exception into a message an officer can act on. */
fun Context.describe(error: Throwable): String = when (error) {
    is SupabaseClient.InvalidCredentials -> getString(R.string.login_invalid)
    is Repository.ProfileMissing -> getString(R.string.login_no_profile)
    is Repository.NotEnrolled -> getString(R.string.not_enrolled)
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
