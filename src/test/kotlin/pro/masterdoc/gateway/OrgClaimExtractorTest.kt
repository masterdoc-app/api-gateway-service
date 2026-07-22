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
    fun `blank or missing org claim returns null`() {
        assertNull(OrgClaimExtractor.orgIdFrom(emptyMap()))
        assertNull(OrgClaimExtractor.orgIdFrom(mapOf("urn:zitadel:iam:org:id" to "  ")))
    }
}
