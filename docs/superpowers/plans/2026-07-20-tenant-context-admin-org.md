# Tenant Context Admin Org Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admin `/admin/users*` APIs scope Zitadel Management calls to the caller's org from JWT (`urn:zitadel:iam:org:id`) via ambient `TenantContext`, without `orgId` in handler/client method signatures.

**Architecture:** Extend `TokenValidator` to return `ValidatedToken(sub, orgId)`. Admin auth enters `withTenant(orgId)` before Zitadel work. `HttpZitadelAdminClient` reads `TenantContext.requireOrgId()` for `x-zitadel-orgid`. Remove runtime `ZITADEL_ORG_ID` / `GatewayConfig.zitadelOrgId`.

**Tech Stack:** Kotlin 2.x, Ktor server, Auth0 JWT (`com.auth0.jwt`), existing JUnit/kotlin.test + `testApplication`.

**Spec:** `docs/superpowers/specs/2026-07-20-tenant-context-admin-org-design.md`

## Global Constraints

- Do not accept org from request body/query/`X-Org-Id`.
- Handler and `ZitadelAdminClient` method signatures must not take `orgId`.
- Org claim name: `urn:zitadel:iam:org:id` (exact).
- Missing/blank org claim on admin paths → `401`.
- Prefer deleting `zitadelOrgId` from gateway config (not leaving dead env).
- Targeted unit tests only: `./gradlew test --tests 'pro.masterdoc.gateway.*'` (no Wasm/Docker/full suite beyond gateway).
- Work on branch `feat/tenant-context-admin-org` from current `main`.

## File map

| File | Role |
|------|------|
| `src/main/kotlin/.../ValidatedToken.kt` | `data class ValidatedToken(subject, orgId)` |
| `src/main/kotlin/.../TenantContext.kt` | ambient org: `withTenant` / `current` / `requireOrgId` |
| `src/main/kotlin/.../Clients.kt` | `TokenValidator` returns `ValidatedToken?`; factories |
| `src/main/kotlin/.../JwksTokenValidator.kt` | verify JWT + extract org claim |
| `src/main/kotlin/.../AdminAuth.kt` | return `ValidatedToken?`; document tenant usage |
| `src/main/kotlin/.../AdminUserRoutes.kt` | `withTenant` around Zitadel calls |
| `src/main/kotlin/.../MeRoutes.kt` | use `validate(...) != null` (ignore org) |
| `src/main/kotlin/.../ZitadelAdminClient.kt` | header from `TenantContext`; drop org env gate |
| `src/main/kotlin/.../GatewayConfig.kt` | remove `zitadelOrgId` |
| `src/test/kotlin/.../TenantContextTest.kt` | unit tests |
| `src/test/kotlin/.../ValidatedTokenOrgClaimTest.kt` | claim extraction / accepting factories |
| `src/test/kotlin/.../AdminUserRoutesTest.kt` | org A vs B + missing org → 401 |
| `src/test/kotlin/.../MeRoutesTest.kt` | still compiles with new validator |
| `docs/SECRETS_AND_DOMAINS.md` | JWT org; drop gateway `ZITADEL_ORG_ID` |
| `.github/workflows/ci.yml` | stop syncing `ZITADEL_ORG_ID` to VPS gateway `.env` |

---

### Task 1: TenantContext + ValidatedToken

**Files:**
- Create: `src/main/kotlin/pro/masterdoc/gateway/ValidatedToken.kt`
- Create: `src/main/kotlin/pro/masterdoc/gateway/TenantContext.kt`
- Create: `src/test/kotlin/pro/masterdoc/gateway/TenantContextTest.kt`

**Interfaces:**
- Produces: `ValidatedToken(subject: String, orgId: String)`
- Produces: `TenantContext.withTenant`, `current()`, `requireOrgId()`

- [ ] **Step 1: Write failing tests**

```kotlin
package pro.masterdoc.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class TenantContextTest {
    @Test
    fun `withTenant sets current and clears after`() = runBlocking {
        assertNull(TenantContext.current())
        TenantContext.withTenant("org-a") {
            assertEquals("org-a", TenantContext.current())
            assertEquals("org-a", TenantContext.requireOrgId())
        }
        assertNull(TenantContext.current())
    }

    @Test
    fun `requireOrgId outside tenant throws`() {
        assertFailsWith<IllegalStateException> {
            TenantContext.requireOrgId()
        }
    }

    @Test
    fun `nested withTenant restores outer`() = runBlocking {
        TenantContext.withTenant("outer") {
            TenantContext.withTenant("inner") {
                assertEquals("inner", TenantContext.requireOrgId())
            }
            assertEquals("outer", TenantContext.requireOrgId())
        }
    }
}
```

- [ ] **Step 2: Run test — expect FAIL (TenantContext missing)**

```bash
./gradlew test --tests 'pro.masterdoc.gateway.TenantContextTest' 
```

Expected: compile failure / class not found `TenantContext`

- [ ] **Step 3: Implement**

`ValidatedToken.kt`:

```kotlin
package pro.masterdoc.gateway

data class ValidatedToken(
    val subject: String,
    val orgId: String,
)
```

`TenantContext.kt`:

```kotlin
package pro.masterdoc.gateway

object TenantContext {
    private val orgId = ThreadLocal<String?>()

    fun current(): String? = orgId.get()

    fun requireOrgId(): String =
        current() ?: error("TenantContext.orgId is not set — call withTenant from authenticated request boundary")

    suspend fun <T> withTenant(orgIdValue: String, block: suspend () -> T): T {
        require(orgIdValue.isNotBlank()) { "orgId must not be blank" }
        val previous = orgId.get()
        orgId.set(orgIdValue)
        return try {
            block()
        } finally {
            if (previous == null) orgId.remove() else orgId.set(previous)
        }
    }
}
```

- [ ] **Step 4: Run tests — expect PASS**

```bash
./gradlew test --tests 'pro.masterdoc.gateway.TenantContextTest'
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pro/masterdoc/gateway/ValidatedToken.kt \
  src/main/kotlin/pro/masterdoc/gateway/TenantContext.kt \
  src/test/kotlin/pro/masterdoc/gateway/TenantContextTest.kt
git commit -m "feat: add TenantContext and ValidatedToken"
```

---

### Task 2: TokenValidator returns ValidatedToken

**Files:**
- Modify: `src/main/kotlin/pro/masterdoc/gateway/Clients.kt` (`TokenValidator` + factories)
- Modify: `src/main/kotlin/pro/masterdoc/gateway/JwksTokenValidator.kt`
- Modify: `src/main/kotlin/pro/masterdoc/gateway/MeRoutes.kt`
- Create: `src/test/kotlin/pro/masterdoc/gateway/OrgClaimExtractorTest.kt`
- Modify: `src/test/kotlin/pro/masterdoc/gateway/MeRoutesTest.kt` (only if compile breaks — factories keep working)

**Interfaces:**
- Consumes: `ValidatedToken`
- Produces: `TokenValidator.validate(bearerToken): ValidatedToken?`
- Produces: `TokenValidator.accepting(subject = "test-sub", orgId = "test-org")`, `rejecting()`
- Produces: `OrgClaimExtractor.orgIdFrom(claims: Map<String, Any?>): String?` (or decode helpers on JWT)

- [ ] **Step 1: Write failing claim extraction test**

```kotlin
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
```

- [ ] **Step 2: Run — expect FAIL**

```bash
./gradlew test --tests 'pro.masterdoc.gateway.OrgClaimExtractorTest'
```

- [ ] **Step 3: Implement extractor + change TokenValidator**

Add `OrgClaimExtractor.kt`:

```kotlin
package pro.masterdoc.gateway

object OrgClaimExtractor {
    const val ORG_ID_CLAIM = "urn:zitadel:iam:org:id"

    fun orgIdFrom(claims: Map<String, Any?>): String? {
        val raw = claims[ORG_ID_CLAIM] ?: return null
        val value = raw.toString().trim()
        return value.takeIf { it.isNotEmpty() }
    }
}
```

In `Clients.kt` replace `TokenValidator`:

```kotlin
fun interface TokenValidator {
    /** Valid token with subject + org; null if invalid / missing org claim. */
    suspend fun validate(bearerToken: String): ValidatedToken?

    companion object {
        fun jwks(issuer: String, jwkSetUri: String): TokenValidator =
            JwksTokenValidator(issuer, jwkSetUri)

        fun accepting(
            subject: String = "test-sub",
            orgId: String = "test-org",
        ): TokenValidator = TokenValidator { ValidatedToken(subject, orgId) }

        fun rejecting(): TokenValidator = TokenValidator { null }
    }
}
```

`JwksTokenValidator.validate`:

```kotlin
override suspend fun validate(bearerToken: String): ValidatedToken? {
    return try {
        val decoded: DecodedJWT = JWT.decode(bearerToken)
        val jwk = jwkProvider.get(decoded.keyId)
        val algorithm = Algorithm.RSA256(jwk.publicKey as RSAPublicKey, null)
        val verified =
            JWT.require(algorithm)
                .withIssuer(issuer)
                .build()
                .verify(bearerToken)
        val subject = verified.subject?.takeIf { it.isNotBlank() } ?: return null
        val orgId = OrgClaimExtractor.orgIdFrom(verified.claims.mapValues { it.value.asString() })
            ?: OrgClaimExtractor.orgIdFrom(
                verified.claims.mapValues { (_, claim) ->
                    runCatching { claim.asString() }.getOrNull()
                        ?: runCatching { claim.as(Any::class.java) }.getOrNull()
                },
            )
        // Prefer simple: claim asString
        val org =
            verified.getClaim(OrgClaimExtractor.ORG_ID_CLAIM).asString()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
        ValidatedToken(subject = subject, orgId = org)
    } catch (_: JWTVerificationException) {
        null
    } catch (_: Exception) {
        null
    }
}
```

Simplify the middle — **use only**:

```kotlin
val org =
    verified.getClaim(OrgClaimExtractor.ORG_ID_CLAIM).asString()?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
return ValidatedToken(subject, org)
```

`MeRoutes.kt` keep `deps.tokenValidator.validate(token) == null` (works with `ValidatedToken?`).

- [ ] **Step 4: Run targeted tests**

```bash
./gradlew test --tests 'pro.masterdoc.gateway.OrgClaimExtractorTest' --tests 'pro.masterdoc.gateway.MeRoutesTest' --tests 'pro.masterdoc.gateway.AdminUserRoutesTest'
```

Expected: PASS (admin still uses env org until Task 3–4; accepting() still returns a token)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pro/masterdoc/gateway/Clients.kt \
  src/main/kotlin/pro/masterdoc/gateway/JwksTokenValidator.kt \
  src/main/kotlin/pro/masterdoc/gateway/OrgClaimExtractor.kt \
  src/test/kotlin/pro/masterdoc/gateway/OrgClaimExtractorTest.kt
git commit -m "feat: validate JWT org claim into ValidatedToken"
```

---

### Task 3: Admin auth + routes use withTenant

**Files:**
- Modify: `src/main/kotlin/pro/masterdoc/gateway/AdminAuth.kt`
- Modify: `src/main/kotlin/pro/masterdoc/gateway/AdminUserRoutes.kt`
- Modify: `src/test/kotlin/pro/masterdoc/gateway/AdminUserRoutesTest.kt`

**Interfaces:**
- Consumes: `ValidatedToken`, `TenantContext.withTenant`
- Produces: `suspend fun ApplicationCall.requireUserInvite(deps): ValidatedToken?`

- [ ] **Step 1: Write failing admin org-scoping test**

Add to `AdminUserRoutesTest.kt`:

```kotlin
private class RecordingOrgAdminClient : ZitadelAdminClient {
    val seenOrgIds = mutableListOf<String>()

    private fun capture(): Nothing {
        seenOrgIds += TenantContext.requireOrgId()
        error("stop-after-capture")
    }

    override suspend fun inviteUser(request: InviteUserRequest): AdminUser {
        seenOrgIds += TenantContext.requireOrgId()
        return FakeZitadelAdminClient().inviteUser(request)
    }

    override suspend fun listUsers(limit: Int, offset: Int): AdminUserList {
        seenOrgIds += TenantContext.requireOrgId()
        return AdminUserList(emptyList(), 0)
    }

    override suspend fun setRoles(userId: String, roles: List<String>): AdminUser {
        seenOrgIds += TenantContext.requireOrgId()
        return FakeZitadelAdminClient().setRoles(userId, roles)
    }

    override suspend fun resendInvite(userId: String) {
        seenOrgIds += TenantContext.requireOrgId()
    }
}

@Test
fun `POST invites uses org id from validated token not env`() = testApplication {
    val recording = RecordingOrgAdminClient()
    application {
        module(
            GatewayConfig.testDefaults(),
            testDeps(
                featureClientWith("user_invite"),
                zitadelAdminClient = recording,
                tokenValidator = TokenValidator.accepting(orgId = "org-from-jwt"),
            ),
        )
    }
    val response =
        client.post("/admin/users/invites") {
            header(HttpHeaders.Authorization, "Bearer good-token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                """{"email":"ivan@company.ru","givenName":"Ivan","familyName":"Petrov","roles":["technologist"]}""",
            )
        }
    assertEquals(HttpStatusCode.Created, response.status)
    assertEquals(listOf("org-from-jwt"), recording.seenOrgIds)
}

@Test
fun `POST invites with validator missing org returns 401`() = testApplication {
    application {
        module(
            GatewayConfig.testDefaults(),
            testDeps(
                featureClientWith("user_invite"),
                tokenValidator = TokenValidator.rejecting(),
            ),
        )
    }
    val response =
        client.post("/admin/users/invites") {
            header(HttpHeaders.Authorization, "Bearer bad")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"email":"a@b.com","givenName":"A","familyName":"B","roles":["admin"]}""")
        }
    assertEquals(HttpStatusCode.Unauthorized, response.status)
}
```

- [ ] **Step 2: Run — expect FAIL** (`seenOrgIds` empty / `IllegalStateException` from `requireOrgId`)

```bash
./gradlew test --tests 'pro.masterdoc.gateway.AdminUserRoutesTest'
```

- [ ] **Step 3: Implement AdminAuth + routes**

`AdminAuth.kt` — change return type:

```kotlin
suspend fun ApplicationCall.requireUserInvite(deps: GatewayDeps): ValidatedToken? {
    val authorization = request.header(HttpHeaders.Authorization)
    if (authorization.isNullOrBlank() || !authorization.startsWith("Bearer ")) {
        respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        return null
    }
    val token = authorization.removePrefix("Bearer ").trim()
    val validated = if (token.isEmpty()) null else deps.tokenValidator.validate(token)
    if (validated == null) {
        respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        return null
    }
    // ... same feature-service checks; on failure return null ...
    if ("user_invite" !in features) {
        respondText("Forbidden", status = HttpStatusCode.Forbidden)
        return null
    }
    return validated
}
```

`AdminUserRoutes.kt` — each handler:

```kotlin
post("/admin/users/invites") {
    val validated = call.requireUserInvite(deps) ?: return@post
    val request = call.receive<InviteUserRequest>()
    ProductRoles.validate(request.roles)?.let { error ->
        call.respondText(error, status = HttpStatusCode.BadRequest)
        return@post
    }
    try {
        TenantContext.withTenant(validated.orgId) {
            val user = deps.zitadelAdminClient.inviteUser(request)
            call.respond(HttpStatusCode.Created, user)
        }
    } catch (e: ZitadelAdminException) {
        call.respondZitadelAdminException(e)
    } catch (_: UpstreamUnavailableException) {
        call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
    }
}
```

Apply the same `validated` + `withTenant` wrap to `GET /admin/users`, `PUT .../roles`, `POST .../resend`.

- [ ] **Step 4: Run AdminUserRoutesTest — expect PASS**

```bash
./gradlew test --tests 'pro.masterdoc.gateway.AdminUserRoutesTest'
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pro/masterdoc/gateway/AdminAuth.kt \
  src/main/kotlin/pro/masterdoc/gateway/AdminUserRoutes.kt \
  src/test/kotlin/pro/masterdoc/gateway/AdminUserRoutesTest.kt
git commit -m "feat: scope admin routes with TenantContext from JWT"
```

---

### Task 4: ZitadelAdminClient header from TenantContext; drop env org

**Files:**
- Modify: `src/main/kotlin/pro/masterdoc/gateway/ZitadelAdminClient.kt`
- Modify: `src/main/kotlin/pro/masterdoc/gateway/GatewayConfig.kt`
- Modify: `docs/SECRETS_AND_DOMAINS.md`
- Modify: `.github/workflows/ci.yml`
- Modify: any `deploy/.env.example` if present with `ZITADEL_ORG_ID`

**Interfaces:**
- Consumes: `TenantContext.requireOrgId()`
- Produces: HTTP client constructed when `zitadelMgmtToken` non-blank only

- [ ] **Step 1: Write unit test for header resolution**

Create `src/test/kotlin/pro/masterdoc/gateway/ZitadelAdminOrgHeaderTest.kt`:

```kotlin
package pro.masterdoc.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class ZitadelAdminOrgHeaderTest {
    @Test
    fun `resolveOrgHeader reads TenantContext`() = runBlocking {
        TenantContext.withTenant("org-xyz") {
            assertEquals("org-xyz", ZitadelAdminOrgHeader.resolve())
        }
    }
}
```

(If you prefer not to extract a helper, skip this file and rely on Task 3 recording test + code review of `applyAuthHeaders`.)

- [ ] **Step 2: Change client + config**

In `ZitadelAdminClient.http`:

```kotlin
fun http(config: GatewayConfig): ZitadelAdminClient {
    if (config.zitadelMgmtToken.isBlank()) {
        return notConfiguredClient("ZITADEL_MGMT_TOKEN not set")
    }
    return HttpZitadelAdminClient(config)
}
```

`applyAuthHeaders`:

```kotlin
private fun io.ktor.client.request.HttpRequestBuilder.applyAuthHeaders() {
    header(HttpHeaders.Authorization, "Bearer ${config.zitadelMgmtToken}")
    header("x-zitadel-orgid", TenantContext.requireOrgId())
}
```

Optional tiny helper (keeps test simple):

```kotlin
internal object ZitadelAdminOrgHeader {
    fun resolve(): String = TenantContext.requireOrgId()
}
```

Remove `zitadelOrgId` from `GatewayConfig` data class, `fromEnv()`, `testDefaults()`.

**docs/SECRETS_AND_DOMAINS.md:**
- Remove `ZITADEL_ORG_ID` from gateway `.env` example and tables for gateway runtime.
- Add note: admin org comes from JWT claim `urn:zitadel:iam:org:id`; platform `ZITADEL_ORG_ID` remains for `masterdoc-zitadel` Terraform only; PAT must manage users across client orgs.

**.github/workflows/ci.yml:**
- Remove `ZITADEL_ORG_ID` from secrets export / `upsert_env` for gateway deploy (keep `ZITADEL_MGMT_TOKEN`, `ZITADEL_PROJECT_ID` if still used for grants).

Verify `zitadelProjectId` is still required for grant APIs — **keep** `ZITADEL_PROJECT_ID`.

- [ ] **Step 3: Full gateway unit tests**

```bash
./gradlew test --tests 'pro.masterdoc.gateway.*'
```

Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/pro/masterdoc/gateway/ZitadelAdminClient.kt \
  src/main/kotlin/pro/masterdoc/gateway/GatewayConfig.kt \
  docs/SECRETS_AND_DOMAINS.md \
  .github/workflows/ci.yml \
  src/test/kotlin/pro/masterdoc/gateway/ZitadelAdminOrgHeaderTest.kt
git commit -m "feat: Zitadel admin org header from TenantContext

Drop gateway ZITADEL_ORG_ID; document JWT org claim ops note."
```

---

### Task 5: Smoke checklist (no heavy local build)

- [ ] **Step 1: Confirm grep clean**

```bash
rg -n 'zitadelOrgId|ZITADEL_ORG_ID' src .github/workflows docs/SECRETS_AND_DOMAINS.md
```

Expected: no matches in `src/` or gateway runtime secrets (spec/plan historical mentions OK under `docs/superpowers/`).

- [ ] **Step 2: Push branch for CI**

```bash
git push -u origin HEAD
```

Monitor: `gh run list --branch feat/tenant-context-admin-org` / `gh run watch`

- [ ] **Step 3: Final commit only if docs/checklist leftover** — otherwise done.

---

## Self-review (plan vs spec)

| Spec item | Task |
|-----------|------|
| `ValidatedToken` + org claim | Task 2 |
| `TenantContext` ambient API | Task 1 |
| Admin `withTenant` after feature check | Task 3 |
| `x-zitadel-orgid` from context | Task 4 |
| Delete `ZITADEL_ORG_ID` from gateway runtime | Task 4 |
| Ops note PAT / terraform org | Task 4 docs |
| Tests org A vs missing claim | Task 3 |
| No org in handler signatures | Task 3–4 |

No TBD placeholders. Types consistent: `ValidatedToken`, `TenantContext.withTenant`, `TokenValidator.accepting(orgId=)`.
