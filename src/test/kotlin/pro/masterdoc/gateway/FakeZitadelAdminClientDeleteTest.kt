package pro.masterdoc.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class FakeZitadelAdminClientDeleteTest {
    @Test
    fun `deleteUser removes invited user`() = runBlocking {
        val fake = FakeZitadelAdminClient()
        val created =
            fake.inviteUser(
                InviteUserRequest("a@b.com", "A", "B", listOf("board")),
            )
        fake.deleteUser(created.id)
        assertEquals(0, fake.listUsers(50, 0).total)
    }

    @Test
    fun `deleteUser removes active user`() = runBlocking {
        val fake = FakeZitadelAdminClient()
        val created =
            fake.inviteUser(
                InviteUserRequest("a@b.com", "A", "B", listOf("board")),
            )
        fake.markActive(created.id)
        fake.deleteUser(created.id)
        assertEquals(0, fake.listUsers(50, 0).total)
    }

    @Test
    fun `deleteUser unknown throws NotFound`() = runBlocking {
        val fake = FakeZitadelAdminClient()
        assertFailsWith<ZitadelAdminException.NotFound> {
            fake.deleteUser("missing")
        }
    }
}
