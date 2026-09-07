package com.eliteguard.checkpoint.net

import com.eliteguard.checkpoint.Config
import com.eliteguard.checkpoint.data.Session
import com.eliteguard.checkpoint.util.reqStr
import com.eliteguard.checkpoint.util.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Minimal Supabase client (GoTrue auth + PostgREST) built on HttpURLConnection so the app has
 * no third-party dependencies. All methods are blocking and must be called off the main thread.
 */
class SupabaseClient(private val session: Session) {

    /** The server answered with an HTTP error. */
    class ApiException(val status: Int, val body: String) : IOException("HTTP $status: ${summarize(body)}") {
        val isConflict: Boolean get() = status == 409
        val isForbidden: Boolean get() = status == 403

        companion object {
            private fun summarize(body: String): String = try {
                val o = JSONObject(body)
                o.str("message") ?: o.str("msg") ?: o.str("error_description") ?: o.str("error") ?: body
            } catch (e: Exception) {
                body
            }.take(300)
        }
    }

    /** The session is no longer valid and the officer must sign in again. */
    class AuthException(message: String) : IOException(message)

    /** Wrong username/password on sign in. */
    class InvalidCredentials : IOException("Invalid username or password")

    // ------------------------------------------------------------------ auth

    fun signIn(username: String, password: String) {
        val email = if (username.contains('@')) username else "$username@${Config.LOGIN_DOMAIN}"
        val body = JSONObject().put("email", email).put("password", password)
        val response = try {
            http("POST", "${Config.SUPABASE_URL}/auth/v1/token?grant_type=password", body.toString(), bearer = null, prefer = null)
        } catch (e: ApiException) {
            if (e.status == 400 || e.status == 401) throw InvalidCredentials()
            throw e
        }
        storeTokens(JSONObject(response))
    }

    fun signOut() {
        val token = session.accessToken
        session.clear()
        if (token != null) {
            try {
                http("POST", "${Config.SUPABASE_URL}/auth/v1/logout", "", bearer = token, prefer = null)
            } catch (e: IOException) {
                // Best effort; the local session is gone regardless.
            }
        }
    }

    /** Exchanges the refresh token for a new access token. Returns false when the session is dead. */
    @Synchronized
    fun refreshTokens(): Boolean {
        val refresh = session.refreshToken ?: return false
        return try {
            val body = JSONObject().put("refresh_token", refresh)
            val response = http("POST", "${Config.SUPABASE_URL}/auth/v1/token?grant_type=refresh_token", body.toString(), bearer = null, prefer = null)
            storeTokens(JSONObject(response))
            true
        } catch (e: ApiException) {
            if (e.status == 400 || e.status == 401 || e.status == 403) false else throw e
        }
    }

    private fun storeTokens(json: JSONObject) {
        val user = json.optJSONObject("user")
        session.saveTokens(
            accessToken = json.reqStr("access_token"),
            refreshToken = json.reqStr("refresh_token"),
            expiresInSeconds = json.optLong("expires_in", 3600L),
            userId = user?.str("id") ?: session.userId ?: throw IllegalStateException("No user id in token response"),
        )
    }

    // ------------------------------------------------------------------ PostgREST

    /** `query` is a raw PostgREST query string, e.g. `select=*&property_id=eq.123&order=name`. */
    fun select(table: String, query: String): JSONArray = JSONArray(rest("GET", table, query, null, null))

    fun insert(table: String, row: JSONObject): JSONObject =
        JSONArray(rest("POST", table, "", row.toString(), "return=representation")).getJSONObject(0)

    /** Insert-or-update on the primary key; safe to retry after a lost connection. */
    fun upsert(table: String, rows: JSONArray): JSONArray =
        JSONArray(rest("POST", table, "", rows.toString(), "resolution=merge-duplicates,return=representation"))

    fun update(table: String, filter: String, changes: JSONObject): JSONArray =
        JSONArray(rest("PATCH", table, filter, changes.toString(), "return=representation"))

    private fun rest(method: String, table: String, query: String, body: String?, prefer: String?): String {
        val url = "${Config.SUPABASE_URL}/rest/v1/$table" + if (query.isNotEmpty()) "?$query" else ""
        return authed(method, url, body, prefer)
    }

    private fun authed(method: String, url: String, body: String?, prefer: String?): String {
        if (!session.isLoggedIn) throw AuthException("Not signed in")
        if (session.expiresAt - EXPIRY_MARGIN_MS < System.currentTimeMillis()) {
            if (!refreshTokens()) throw AuthException("Session expired")
        }
        return try {
            http(method, url, body, bearer = session.accessToken, prefer = prefer)
        } catch (e: ApiException) {
            if (e.status != 401) throw e
            if (!refreshTokens()) throw AuthException("Session expired")
            http(method, url, body, bearer = session.accessToken, prefer = prefer)
        }
    }

    // ------------------------------------------------------------------ HTTP

    private fun http(method: String, url: String, body: String?, bearer: String?, prefer: String?): String {
        val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("apikey", Config.SUPABASE_ANON_KEY)
            connection.setRequestProperty("Authorization", "Bearer ${bearer ?: Config.SUPABASE_ANON_KEY}")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Client-Info", "eliteguard-tours-android/${Config.VERSION_NAME}")
            if (prefer != null) connection.setRequestProperty("Prefer", prefer)
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status >= 400) throw ApiException(status, text)
            return text
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000
        private const val EXPIRY_MARGIN_MS = 60_000L

        fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
