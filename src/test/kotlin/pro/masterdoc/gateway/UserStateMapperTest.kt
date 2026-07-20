package pro.masterdoc.gateway

import kotlin.test.Test
import kotlin.test.assertEquals

class UserStateMapperTest {
    @Test
    fun `maps initial to invited`() {
        assertEquals("invited", UserStateMapper.fromZitadel("USER_STATE_INITIAL"))
    }

    @Test
    fun `maps active`() {
        assertEquals("active", UserStateMapper.fromZitadel("USER_STATE_ACTIVE"))
    }

    @Test
    fun `maps inactive and locked`() {
        assertEquals("inactive", UserStateMapper.fromZitadel("USER_STATE_INACTIVE"))
        assertEquals("inactive", UserStateMapper.fromZitadel("USER_STATE_LOCKED"))
        assertEquals("inactive", UserStateMapper.fromZitadel("USER_STATE_DELETED"))
    }

    @Test
    fun `unknown defaults to inactive`() {
        assertEquals("inactive", UserStateMapper.fromZitadel(null))
        assertEquals("inactive", UserStateMapper.fromZitadel("USER_STATE_SOMETHING"))
    }
}
