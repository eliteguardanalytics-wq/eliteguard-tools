package com.eliteguard.checkpoint

/**
 * Backend configuration.
 *
 * The app runs against the Elite Guard **incident reporting** Supabase project, sharing that
 * project's sign-in accounts and its `properties` table. The publishable (anon) key is safe to
 * ship: the project's row level security policies decide what a signed-in officer may read
 * or write.
 *
 * To point the app at a different project, change the four constants below and re-run
 * `supabase/checkpoint_schema.sql` against that project.
 */
object Config {
    const val SUPABASE_URL = "https://fmfcfepwindmioiowvfe.supabase.co"
    const val SUPABASE_ANON_KEY = "sb_publishable_kcjQOgR-2n1s8nCShaQMYw_IxVicl_H"

    /** Table holding one row per portal login, keyed to the Supabase Auth user. */
    const val ACCOUNTS_TABLE = "incident_portal_accounts"

    /** Table holding the client sites. */
    const val PROPERTIES_TABLE = "properties"

    /**
     * Officers sign in with a username. Supabase Auth authenticates on an e-mail address, so the
     * username is turned into one by appending a domain, the same way the incident reporting
     * portal does it.
     *
     * The exact domain that project uses is not recorded here, so the app tries these in order on
     * the first sign-in and permanently remembers whichever one works (see Session.loginDomain).
     * Every later sign-in makes a single request. Once you know the real domain, put it first in
     * this list, or make it the only entry.
     *
     * To find it: open any row in the incident project's auth.users table and look at its e-mail,
     * or find the line in the portal's login code that builds the address.
     */
    val LOGIN_DOMAINS = listOf(
        "eliteguard.internal",
        "eliteguard.local",
        "eliteguard.app",
    )

    /** Roles allowed to create checkpoints and enrol NFC tags from the phone. */
    val MANAGER_ROLES = setOf("admin")

    /**
     * Columns searched, in order, for the name to show in the app. The first one present on the
     * account row wins, so the app works whichever naming the accounts table uses.
     */
    val DISPLAY_NAME_COLUMNS = listOf("display_name", "full_name", "name", "username", "email")

    /** Columns searched, in order, for the officer's login name. */
    val USERNAME_COLUMNS = listOf("username", "email", "display_name", "full_name", "name")

    const val VERSION_NAME = "1.1.0"
}
