package pro.masterdoc.gateway

import kotlin.test.Test
import kotlin.test.assertEquals

class UserStateMapperTest {
    @Test
    fun `maps initial to invited`() {
        assertEquals("invited", UserStateMapper.fromZitadel("USER_STATE_INITIAL"))
        assertEquals("invited", UserStateMapper.fromZitadel("USER_STATE_INITIAL", emailVerified = true))
    }

    @Test
    fun `maps active by email verification`() {
        assertEquals("active", UserStateMapper.fromZitadel("USER_STATE_ACTIVE", emailVerified = true))
        assertEquals("invited", UserStateMapper.fromZitadel("USER_STATE_ACTIVE", emailVerified = false))
        assertEquals("invited", UserStateMapper.fromZitadel("USER_STATE_ACTIVE"))
    }

    @Test
    fun `maps inactive and locked`() {
        assertEquals("inactive", UserStateMapper.fromZitadel("USER_STATE_INACTIVE"))
        assertEquals("inactive", UserStateMapper.fromZitadel("USER_STATE_INACTIVE", emailVerified = true))
        assertEquals("inactive", UserStateMapper.fromZitadel("USER_STATE_LOCKED"))
        assertEquals("inactive", UserStateMapper.fromZitadel("USER_STATE_DELETED"))
    }

    @Test
    fun `unknown defaults to inactive`() {
        assertEquals("inactive", UserStateMapper.fromZitadel(null))
        assertEquals("inactive", UserStateMapper.fromZitadel("USER_STATE_SOMETHING"))
    }
}
