package pro.masterdoc.gateway

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ZitadelLoginClientTest {
    @Test
    fun `session PAT 401 is upstream failure`() {
        assertFailsWith<UpstreamUnavailableException> {
            parseSessionResponse(
                HttpStatusCode.Unauthorized,
                """{"code":16,"message":"Unauthenticated"}""",
            )
        }
    }

    @Test
    fun `session IAM 403 is upstream failure`() {
        assertFailsWith<UpstreamUnavailableException> {
            parseSessionResponse(
                HttpStatusCode.Forbidden,
                """{"code":7,"message":"Permission denied"}""",
            )
        }
    }

    @Test
    fun `session password rejection is invalid credentials`() {
        val result =
            parseSessionResponse(
                HttpStatusCode.BadRequest,
                """{"code":3,"message":"Password is invalid (COMMAND-3M0fs)"}""",
            )

        assertEquals(SessionResponse.InvalidCredentials, result)
    }

    @Test
    fun `unrecognized session 400 is upstream failure`() {
        assertFailsWith<UpstreamUnavailableException> {
            parseSessionResponse(
                HttpStatusCode.BadRequest,
                """{"code":3,"message":"Request configuration is invalid"}""",
            )
        }
    }

    @Test
    fun `successful session response parses credentials`() {
        val result =
            parseSessionResponse(
                HttpStatusCode.OK,
                """{"sessionId":"session","sessionToken":"token"}""",
            )

        val authenticated = assertIs<SessionResponse.Authenticated>(result)
        assertEquals("session", authenticated.sessionId)
        assertEquals("token", authenticated.sessionToken)
    }

    @Test
    fun `callback URL code is extracted`() {
        assertEquals(
            "authorization-code",
            parseCodeFromCallback("masterdoc://auth/callback?code=authorization-code&state=state"),
        )
    }
}
