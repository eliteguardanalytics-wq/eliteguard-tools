package com.eliteguard.checkpoint.net

import com.eliteguard.checkpoint.Config
import com.eliteguard.checkpoint.util.str
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

/**
 * Works out which backend a portal host uses.
 *
 * A host may publish `https://<host>/app-config.json` holding its Supabase project:
 *
 *     { "supabase_url": "https://xxxx.supabase.co", "supabase_anon_key": "sb_publishable_..." }
 *
 * That is what lets a second tenant, say `xyzsecurity.siloam.one`, work without a new build.
 * When the file is absent or unreachable — which is the case for Elite Guard today — the
 * compiled-in default is used instead, so enrolment still works.
 */
object TenantConfig {

    data class Backend(val url: String, val anonKey: String, val fromHost: Boolean)

    val default: Backend
        get() = Backend(Config.DEFAULT_SUPABASE_URL, Config.DEFAULT_SUPABASE_ANON_KEY, fromHost = false)

    /** Blocking. Never throws: an unreachable or malformed file just means the default. */
    fun resolve(portalHost: String): Backend {
        val body = try {
            fetch("https://$portalHost/app-config.json")
        } catch (e: Exception) {
            null
        } ?: return default
        return try {
            val json = JSONObject(body)
            val url = json.str("supabase_url")?.trimEnd('/')
            val key = json.str("supabase_anon_key")
            if (url.isNullOrBlank() || key.isNullOrBlank() || !url.startsWith("https://")) {
                default
            } else {
                Backend(url, key, fromHost = true)
            }
        } catch (e: Exception) {
            default
        }
    }

    private fun fetch(url: String): String? {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            // Short, because a host with no such file is the normal case and enrolment waits on it.
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200) return null
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
