package pro.masterdoc.gateway

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZitadelHttpErrorMappingTest {
    @Test
    fun `maps 409 to Conflict`() {
        val ex = assertFailsWith<ZitadelAdminException.Conflict> {
            mapZitadelHttpError(HttpStatusCode.Conflict, "duplicate")
        }
        assertEquals("duplicate", ex.message)
    }

    @Test
    fun `maps 404 to NotFound`() {
        assertFailsWith<ZitadelAdminException.NotFound> {
            mapZitadelHttpError(HttpStatusCode.NotFound, "missing")
        }
    }

    @Test
    fun `maps 400 to BadRequest`() {
        assertFailsWith<ZitadelAdminException.BadRequest> {
            mapZitadelHttpError(HttpStatusCode.BadRequest, "invalid")
        }
    }

    @Test
    fun `maps other statuses to Upstream`() {
        val ex = assertFailsWith<ZitadelAdminException.Upstream> {
            mapZitadelHttpError(HttpStatusCode.InternalServerError, "boom")
        }
        assertEquals("zitadel returned 500: boom", ex.message)
    }

    @Test
    fun `detects duplicate email from slug and message`() {
        assertTrue(
            isDuplicateEmailError(
                HttpStatusCode.BadRequest,
                """{"code":"ALREADY_EXISTS","message":"Email already exists"}""",
                "Email already exists",
            ),
        )
        assertTrue(
            isDuplicateEmailError(
                HttpStatusCode.Conflict,
                """{"message":"user with email already registered"}""",
                "user with email already registered",
            ),
        )
        assertFalse(
            isDuplicateEmailError(
                HttpStatusCode.BadRequest,
                """{"message":"givenName is required"}""",
                "givenName is required",
            ),
        )
    }
}
