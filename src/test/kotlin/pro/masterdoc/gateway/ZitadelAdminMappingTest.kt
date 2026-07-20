package pro.masterdoc.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ZitadelAdminMappingTest {
    @Test
    fun `maps management user with v1 profile names`() {
        val user =
            ZitadelManagementUser(
                id = "u1",
                state = "USER_STATE_INITIAL",
                human =
                    ZitadelHuman(
                        profile = ZitadelHumanProfile(firstName = "Jane", lastName = "Doe"),
                        email = ZitadelHumanEmail(email = "jane@example.com"),
                    ),
            )

        val admin = ZitadelAdminMapping.toAdminUser(user, roles = listOf("admin"), includeInviteSent = true)

        assertEquals("u1", admin.id)
        assertEquals("Jane", admin.givenName)
        assertEquals("Doe", admin.familyName)
        assertEquals("invited", admin.state)
        assertEquals(true, admin.inviteSent)
    }

    @Test
    fun `omits inviteSent on list mapping`() {
        val user =
            ZitadelManagementUser(
                id = "u2",
                state = "USER_STATE_ACTIVE",
                human =
                    ZitadelHuman(
                        profile = ZitadelHumanProfile(givenName = "John", familyName = "Smith"),
                        email = ZitadelHumanEmail(email = "john@example.com"),
                    ),
            )

        val admin = ZitadelAdminMapping.toAdminUser(user, roles = emptyList(), includeInviteSent = false)

        assertEquals("active", admin.state)
        assertNull(admin.inviteSent)
    }
}
