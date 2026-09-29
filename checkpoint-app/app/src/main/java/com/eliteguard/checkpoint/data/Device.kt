package com.eliteguard.checkpoint.data

import android.content.Context
import com.eliteguard.checkpoint.Config

/**
 * What this phone is. Set once during enrolment and then left alone: the portal host, the site
 * license it was activated with, the site it belongs to, and the backend that host resolved to.
 *
 * Kept separate from [Session] on purpose. Signing out clears the officer, not the device, so
 * enrolment survives every sign-out and only a deliberate reset undoes it.
 */
class Device(context: Context) {

    private val prefs = context.getSharedPreferences("device", Context.MODE_PRIVATE)

    /** Host the installer typed, normalised: no scheme, no trailing slash, lower case. */
    var portalHost: String?
        get() = prefs.getString("portal_host", null)
        private set(value) = prefs.edit().putString("portal_host", value).apply()

    var licenseKey: String?
        get() = prefs.getString("license_key", null)
        private set(value) = prefs.edit().putString("license_key", value).apply()

    /** The one site this phone may see. Null until enrolled. */
    var propertyId: String?
        get() = prefs.getString("property_id", null)
        private set(value) = prefs.edit().putString("property_id", value).apply()

    var propertyName: String?
        get() = prefs.getString("property_name", null)
        private set(value) = prefs.edit().putString("property_name", value).apply()

    /** Backend the portal host resolved to, falling back to the compiled-in default. */
    val backendUrl: String
        get() = prefs.getString("backend_url", null) ?: Config.DEFAULT_SUPABASE_URL

    val backendKey: String
        get() = prefs.getString("backend_key", null) ?: Config.DEFAULT_SUPABASE_ANON_KEY

    val isEnrolled: Boolean get() = propertyId != null

    val siteName: String get() = propertyName ?: "this site"

    /** True once this phone has told the server it exists, so it is only attempted once. */
    var registered: Boolean
        get() = prefs.getBoolean("registered", false)
        set(value) = prefs.edit().putBoolean("registered", value).apply()

    fun enrol(portalHost: String, licenseKey: String, propertyId: String, propertyName: String, backendUrl: String, backendKey: String) {
        prefs.edit()
            .putString("portal_host", portalHost)
            .putString("license_key", licenseKey)
            .putString("property_id", propertyId)
            .putString("property_name", propertyName)
            .putString("backend_url", backendUrl)
            .putString("backend_key", backendKey)
            .putBoolean("registered", false)
            .apply()
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    companion object {
        /**
         * Accepts what someone actually types: a bare host, a full URL, a trailing slash, stray
         * spaces, any casing. Returns null when nothing host-shaped is left.
         */
        fun normalizeHost(typed: String): String? {
            var h = typed.trim().lowercase()
            h = h.removePrefix("https://").removePrefix("http://")
            h = h.substringBefore('/').substringBefore('?').trim()
            if (h.isEmpty() || !h.contains('.') || h.startsWith('.') || h.endsWith('.')) return null
            return h
        }

        /** Strips the grouping a printed license key may carry, so it can be typed any way. */
        fun normalizeLicense(typed: String): String = typed.filter { it.isLetterOrDigit() }.uppercase()
    }
}
