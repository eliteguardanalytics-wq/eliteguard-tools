package com.eliteguard.checkpoint.net

import com.eliteguard.checkpoint.Config
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/** Exercises the real username -> e-mail resolution used at sign-in. */
class LoginDomainTest {

    /** Records every address tried and authenticates only [accepts]. */
    private class Backend(private val accepts: String?) {
        val tried = mutableListOf<String>()
        fun grant(address: String): String {
            tried += address
            if (address != accepts) throw SupabaseClient.InvalidCredentials()
            return """{"access_token":"a","refresh_token":"r","expires_in":3600,"user":{"id":"u1"}}"""
        }
    }

    @Test fun firstCandidateWinsWithASingleRequest() {
        val backend = Backend("jess@${Config.LOGIN_DOMAINS[0]}")
        val (learned, _) = SupabaseClient.resolveSignIn("jess", null, backend::grant)
        assertEquals(Config.LOGIN_DOMAINS[0], learned)
        assertEquals(listOf("jess@${Config.LOGIN_DOMAINS[0]}"), backend.tried)
    }

    @Test fun probesInOrderUntilOneAuthenticates() {
        val target = Config.LOGIN_DOMAINS.last()
        val backend = Backend("jess@$target")
        val (learned, _) = SupabaseClient.resolveSignIn("jess", null, backend::grant)
        assertEquals(target, learned)
        assertEquals(Config.LOGIN_DOMAINS.map { "jess@$it" }, backend.tried)
    }

    @Test fun aKnownDomainMakesExactlyOneRequest() {
        val backend = Backend("jess@real.example")
        val (learned, _) = SupabaseClient.resolveSignIn("jess", "real.example", backend::grant)
        assertEquals("real.example", learned)
        assertEquals(1, backend.tried.size)
    }

    /** A remembered domain must not silently fall back to probing, or a typo costs N requests. */
    @Test fun aKnownDomainDoesNotFallBackToProbing() {
        val backend = Backend(null)
        try {
            SupabaseClient.resolveSignIn("jess", "real.example", backend::grant)
            fail("expected InvalidCredentials")
        } catch (e: SupabaseClient.InvalidCredentials) {
            assertEquals(listOf("jess@real.example"), backend.tried)
        }
    }

    @Test fun aTypedAddressIsUsedVerbatimAndTeachesNothing() {
        val backend = Backend("boss@elsewhere.com")
        val (learned, _) = SupabaseClient.resolveSignIn("boss@elsewhere.com", null, backend::grant)
        assertNull("a typed address must not be mistaken for the portal convention", learned)
        assertEquals(listOf("boss@elsewhere.com"), backend.tried)
    }

    @Test fun aWrongPasswordCostsAtMostOneRequestPerCandidate() {
        val backend = Backend(null)
        try {
            SupabaseClient.resolveSignIn("jess", null, backend::grant)
            fail("expected InvalidCredentials")
        } catch (e: SupabaseClient.InvalidCredentials) {
            assertEquals(Config.LOGIN_DOMAINS.size, backend.tried.size)
        }
    }

    /** A dead network must surface as itself, not as "invalid username or password". */
    @Test fun aNetworkFailureAbortsImmediately() {
        val tried = mutableListOf<String>()
        try {
            SupabaseClient.resolveSignIn("jess", null) { tried += it; throw IOException("no route to host") }
            fail("expected the IOException to propagate")
        } catch (e: SupabaseClient.InvalidCredentials) {
            fail("network failure was misreported as bad credentials")
        } catch (e: IOException) {
            assertEquals("no route to host", e.message)
            assertEquals("must not try the remaining candidates", 1, tried.size)
        }
    }

    /** A 500 from Supabase must not be reported as bad credentials either. */
    @Test fun aServerErrorAbortsImmediately() {
        val tried = mutableListOf<String>()
        try {
            SupabaseClient.resolveSignIn("jess", null) { tried += it; throw SupabaseClient.ApiException(500, "{}") }
            fail("expected the ApiException to propagate")
        } catch (e: SupabaseClient.ApiException) {
            assertEquals(500, e.status)
            assertEquals(1, tried.size)
        }
    }

    @Test fun candidateListIsSaneAndOrdered() {
        assertTrue("need at least one candidate domain", Config.LOGIN_DOMAINS.isNotEmpty())
        assertEquals("candidates must be unique", Config.LOGIN_DOMAINS.size, Config.LOGIN_DOMAINS.toSet().size)
        assertTrue("no candidate may contain an @", Config.LOGIN_DOMAINS.none { it.contains('@') })
    }
}
