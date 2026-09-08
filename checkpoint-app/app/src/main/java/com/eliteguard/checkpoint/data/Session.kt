package com.eliteguard.checkpoint.data

import android.content.Context
import com.eliteguard.checkpoint.Config

/** Persisted sign-in state (tokens + the officer's profile). Stored in app-private preferences. */
class Session(context: Context) {

    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        set(value) = prefs.edit().putString("access_token", value).apply()

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    /** Epoch millis when the access token expires. */
    var expiresAt: Long
        get() = prefs.getLong("expires_at", 0L)
        set(value) = prefs.edit().putLong("expires_at", value).apply()

    var userId: String?
        get() = prefs.getString("user_id", null)
        set(value) = prefs.edit().putString("user_id", value).apply()

    var username: String?
        get() = prefs.getString("username", null)
        set(value) = prefs.edit().putString("username", value).apply()

    var displayName: String?
        get() = prefs.getString("display_name", null)
        set(value) = prefs.edit().putString("display_name", value).apply()

    var role: String?
        get() = prefs.getString("role", null)
        set(value) = prefs.edit().putString("role", value).apply()

    /**
     * The e-mail domain that successfully authenticated on this phone. Null until the first
     * successful sign-in. Deliberately survives signing out: it is a fact about the backend,
     * not about the officer, and keeping it means later sign-ins take one request instead of
     * probing the candidate list again.
     */
    var loginDomain: String?
        get() = prefs.getString("login_domain", null)
        set(value) = prefs.edit().putString("login_domain", value).apply()

    val isLoggedIn: Boolean get() = accessToken != null && userId != null

    /** Roles allowed to create checkpoints and enroll tags from the phone. */
    val canManageCheckpoints: Boolean get() = role?.trim()?.lowercase() in Config.MANAGER_ROLES

    val officerName: String get() = displayName?.takeIf { it.isNotBlank() } ?: username ?: "Officer"

    fun saveTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long, userId: String) {
        prefs.edit()
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .putLong("expires_at", System.currentTimeMillis() + expiresInSeconds * 1000L)
            .putString("user_id", userId)
            .apply()
    }

    fun saveProfile(username: String, displayName: String?, role: String?) {
        prefs.edit()
            .putString("username", username)
            .putString("display_name", displayName)
            .putString("role", role)
            .apply()
    }

    fun clear() {
        val domain = loginDomain
        prefs.edit().clear().apply()
        if (domain != null) loginDomain = domain
    }

}
