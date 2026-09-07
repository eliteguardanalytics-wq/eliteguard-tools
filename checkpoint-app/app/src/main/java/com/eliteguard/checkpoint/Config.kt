package com.eliteguard.checkpoint

/**
 * Backend configuration. The Supabase project and publishable (anon) key are the same ones
 * used by the Elite Guard web tools; row level security on the server decides what a
 * signed-in officer may read or write.
 */
object Config {
    const val SUPABASE_URL = "https://blsnxyhnuckpvbkoixqa.supabase.co"
    const val SUPABASE_ANON_KEY = "sb_publishable_4_iqlW98eU6gtYI3uUku6Q_qARTbydT"

    /** Usernames are turned into e-mail addresses on this domain, matching the web tools. */
    const val LOGIN_DOMAIN = "eliteguard.internal"

    const val VERSION_NAME = "1.0.0"
}
