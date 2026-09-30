package com.eliteguard.checkpoint

/**
 * Compile-time defaults.
 *
 * The backend is not fixed at build time any more. A phone is enrolled once against a portal
 * host (for example `eliteguard.siloam.one`) and a site license key, and the host decides which
 * Supabase project the app talks to — see [com.eliteguard.checkpoint.net.TenantConfig]. The
 * values below are only the fallback used when a host publishes no configuration of its own,
 * which is the case for Elite Guard today.
 */
object Config {
    /** Backend used when the portal host publishes no `app-config.json` of its own. */
    const val DEFAULT_SUPABASE_URL = "https://fmfcfepwindmioiowvfe.supabase.co"
    const val DEFAULT_SUPABASE_ANON_KEY = "sb_publishable_kcjQOgR-2n1s8nCShaQMYw_IxVicl_H"

    /** Offered on the enrolment screen so the common case is a single field to fill in. */
    const val DEFAULT_PORTAL_HOST = "eliteguard.siloam.one"

    /** Table holding one row per portal login, keyed to the Supabase Auth user. */
    const val ACCOUNTS_TABLE = "incident_portal_accounts"

    /** Table holding the client sites. */
    const val PROPERTIES_TABLE = "properties"

    /**
     * Officers sign in with a username. Supabase Auth authenticates on an e-mail address, so the
     * username is turned into one by appending a domain. The exact domain is not recorded here,
     * so the app tries these in order on the first sign-in and permanently remembers whichever
     * one works (see Session.loginDomain). Every later sign-in makes a single request.
     */
    val LOGIN_DOMAINS = listOf(
        "eliteguard.internal",
        "eliteguard.local",
        "eliteguard.app",
    )

    /** Roles allowed to create tours and checkpoints and to program NFC tags from the phone. */
    val MANAGER_ROLES = setOf("admin")

    /**
     * Columns searched, in order, for the name to show in the app. The first one present on the
     * account row wins, so the app works whichever naming the accounts table uses.
     */
    val DISPLAY_NAME_COLUMNS = listOf("display_name", "full_name", "name", "username", "email")

    /** Columns searched, in order, for the officer's login name. */
    val USERNAME_COLUMNS = listOf("username", "email", "display_name", "full_name", "name")

    const val VERSION_NAME = "3.1.0"
}
