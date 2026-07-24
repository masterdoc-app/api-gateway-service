package pro.masterdoc.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OrgClaimExtractorTest {
    @Test
    fun `reads urn zitadel org id claim`() {
        val claims = mapOf("urn:zitadel:iam:org:id" to "382623622436487171")
        assertEquals("382623622436487171", OrgClaimExtractor.orgIdFrom(claims))
    }

    @Test
    fun `reads resourceowner id when org id missing`() {
        val claims = mapOf("urn:zitadel:iam:user:resourceowner:id" to "382715225649971203")
        assertEquals("382715225649971203", OrgClaimExtractor.orgIdFrom(claims))
    }

    @Test
    fun `prefers org id over resourceowner`() {
        val claims =
            mapOf(
                "urn:zitadel:iam:org:id" to "org-preferred",
                "urn:zitadel:iam:user:resourceowner:id" to "org-home",
            )
        assertEquals("org-preferred", OrgClaimExtractor.orgIdFrom(claims))
    }

    @Test
    fun `blank or missing org claim returns null`() {
        assertNull(OrgClaimExtractor.orgIdFrom(emptyMap()))
        assertNull(OrgClaimExtractor.orgIdFrom(mapOf("urn:zitadel:iam:org:id" to "  ")))
        assertNull(OrgClaimExtractor.orgIdFrom(mapOf("urn:zitadel:iam:user:resourceowner:id" to "  ")))
    }
}
