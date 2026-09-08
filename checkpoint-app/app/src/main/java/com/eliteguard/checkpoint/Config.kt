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
     * Officers may sign in with their full e-mail address. If they type a bare username instead,
     * this domain is appended before the credentials are sent to Supabase Auth.
     */
    const val LOGIN_DOMAIN = "eliteguard.internal"

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
