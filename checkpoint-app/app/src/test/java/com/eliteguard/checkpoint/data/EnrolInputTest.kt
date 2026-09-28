package com.eliteguard.checkpoint.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Enrolment is typed by an installer on a phone keyboard, once, possibly in a stairwell. Both
 * fields are normalised before they reach the server, so the shape of what was typed cannot be
 * the reason a valid licence is rejected.
 */
class EnrolInputTest {

    @Test fun aPlainHostPassesThrough() {
        assertEquals("eliteguard.siloam.one", Device.normalizeHost("eliteguard.siloam.one"))
    }

    @Test fun schemeSlashesCasingAndSpacesAreAllStripped() {
        for (typed in listOf(
            "https://eliteguard.siloam.one",
            "http://eliteguard.siloam.one",
            "ELITEGUARD.SILOAM.ONE",
            "  eliteguard.siloam.one  ",
            "https://eliteguard.siloam.one/",
            "eliteguard.siloam.one/login",
            "https://ELITEGUARD.Siloam.One/app?x=1",
        )) {
            assertEquals("normalising: $typed", "eliteguard.siloam.one", Device.normalizeHost(typed))
        }
    }

    @Test fun aDifferentTenantIsKeptDistinct() {
        assertEquals("xyzsecurity.siloam.one", Device.normalizeHost("XYZSecurity.Siloam.One/"))
    }

    /** Anything without a real hostname must be refused locally, not sent to the server. */
    @Test fun nonsenseIsRejected() {
        for (typed in listOf("", "   ", "eliteguard", "https://", "/", ".", ".com", "eliteguard.", "https:// ")) {
            assertNull("should reject: '$typed'", Device.normalizeHost(typed))
        }
    }

    @Test fun licenceGroupingAndCasingDoNotMatter() {
        val expected = "A1B2C3D4E5F60718"
        for (typed in listOf(
            "A1B2C3D4E5F60718",
            "a1b2c3d4e5f60718",
            "A1B2-C3D4-E5F6-0718",
            "a1b2 c3d4 e5f6 0718",
            "  A1B2-c3d4 E5F6-0718  ",
            "A1B2_C3D4.E5F6/0718",
        )) {
            assertEquals("normalising: $typed", expected, Device.normalizeLicense(typed))
        }
    }

    @Test fun anEmptyLicenceNormalisesToEmptyRatherThanThrowing() {
        assertEquals("", Device.normalizeLicense(""))
        assertEquals("", Device.normalizeLicense("----"))
        assertEquals("", Device.normalizeLicense("   "))
    }
}
