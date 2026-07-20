# Admin user invite REST — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose gateway REST `POST/GET/PUT /admin/users*` (invite, list, roles, resend) gated by feature `user_invite`, backed by Zitadel Management/User API; map `admin` → `user_invite` in feature-service.

**Architecture:** Client → api-gateway BFF (JWT + `/me` features check) → Zitadel with machine PAT. feature-service only adds the feature key for role `admin`. No client UI in this plan.

**Tech Stack:** Kotlin, Ktor 3 (gateway), kotlinx.serialization, JUnit/kotlin.test + Ktor `testApplication`; Spring Boot feature-service (JUnit 5); Zitadel Management / User v2 HTTP.

**Spec:** `docs/superpowers/specs/2026-07-20-invite-admin-rest-design.md`

## Global Constraints

- JSON **camelCase** on public API (`email`, `givenName`, `roles`, `inviteSent`)
- Auth: `Authorization: Bearer`; no `user_invite` → **403**; bad/missing token → **401**
- Valid roles only: `admin`, `dispatcher`, `engineer`, `requester`, `reporter`, `technologist`
- Empty `roles` on PUT → **400**
- Errors: prefer short text bodies (`Unauthorized`, `Forbidden`, `Bad Request`, …) consistent with existing `/me`
- Do **not** build local Wasm/client; gateway/feature tests via Gradle in CI or remote as project rules require
- No SMTP/UI work in this plan

## File map

| File | Responsibility |
|------|----------------|
| `feature-service/.../RoleFeatureResolver.kt` | `admin` → `user_invite` |
| `feature-service/.../RoleFeatureResolverTest.kt` | Cover admin mapping |
| `api-gateway-service/.../ProductRoles.kt` | Allowed role keys + validation |
| `api-gateway-service/.../AdminUserModels.kt` | Request/response DTOs |
| `api-gateway-service/.../UserStateMapper.kt` | Zitadel state → `invited`/`active`/`inactive` |
| `api-gateway-service/.../ZitadelAdminClient.kt` | Interface + HTTP + fake for tests |
| `api-gateway-service/.../AdminAuth.kt` | JWT + feature `user_invite` via feature-service `/me` |
| `api-gateway-service/.../AdminUserRoutes.kt` | HTTP routes |
| `api-gateway-service/.../AdminUserRoutesTest.kt` | Contract tests |
| `api-gateway-service/.../UserStateMapperTest.kt` | State mapping |
| `api-gateway-service/.../GatewayConfig.kt` | `zitadelOrgId`, `zitadelProjectId`, `zitadelMgmtToken` |
| `api-gateway-service/.../Application.kt` | Wire deps + install routes |
| `api-gateway-service/openapi.yaml` | Document `/admin/users*` |
| `api-gateway-service/deploy/.env.example` + `docs/SECRETS_AND_DOMAINS.md` | New secrets |

---

### Task 1: feature-service — `admin` → `user_invite`

**Files:**
- Modify: `feature-service/src/main/kotlin/pro/masterdoc/feature/features/RoleFeatureResolver.kt`
- Test: `feature-service/src/test/kotlin/pro/masterdoc/feature/features/RoleFeatureResolverTest.kt`

**Interfaces:**
- Consumes: existing `RoleFeatureResolver.resolve(roles: List<String>): List<String>`
- Produces: when `"admin"` in roles, result contains `"user_invite"`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `admin role gets user_invite feature`() {
    val features = resolver.resolve(listOf("admin"))
    assertTrue(features.contains("user_invite"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd feature-service && ./gradlew test --tests pro.masterdoc.feature.features.RoleFeatureResolverTest`

Expected: FAIL — `admin` branch missing / assertion false

- [ ] **Step 3: Minimal implementation**

In `RoleFeatureResolver.resolve`, add:

```kotlin
"admin" -> features.add("user_invite")
```

Keep existing `dispatcher` / `engineer` / `technologist` branches unchanged.

- [ ] **Step 4: Run tests to verify pass**

Run: `./gradlew test --tests pro.masterdoc.feature.features.RoleFeatureResolverTest`  
Expected: PASS (all tests in class)

- [ ] **Step 5: Commit**

```bash
cd feature-service
git add src/main/kotlin/pro/masterdoc/feature/features/RoleFeatureResolver.kt \
        src/test/kotlin/pro/masterdoc/feature/features/RoleFeatureResolverTest.kt
git commit -m "feat: map admin role to user_invite feature"
```

---

### Task 2: Product roles + user state mapper

**Files:**
- Create: `api-gateway-service/src/main/kotlin/pro/masterdoc/gateway/ProductRoles.kt`
- Create: `api-gateway-service/src/main/kotlin/pro/masterdoc/gateway/UserStateMapper.kt`
- Create: `api-gateway-service/src/test/kotlin/pro/masterdoc/gateway/UserStateMapperTest.kt`
- Create: `api-gateway-service/src/test/kotlin/pro/masterdoc/gateway/ProductRolesTest.kt`

**Interfaces:**
- Produces:
  - `object ProductRoles { val ALL: Set<String>; fun validate(roles: List<String>): String? }` — returns error message or `null` if ok
  - `object UserStateMapper { fun fromZitadel(state: String?): String }` — returns `invited` | `active` | `inactive`

- [ ] **Step 1: Failing tests**

```kotlin
// ProductRolesTest.kt
@Test
fun `rejects unknown role`() {
    assertEquals("Unknown role: foo", ProductRoles.validate(listOf("technologist", "foo")))
}

@Test
fun `rejects empty roles`() {
    assertEquals("roles must not be empty", ProductRoles.validate(emptyList()))
}

@Test
fun `accepts known roles`() {
    assertEquals(null, ProductRoles.validate(listOf("admin", "technologist")))
}

// UserStateMapperTest.kt
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
```

- [ ] **Step 2: Run tests — expect FAIL**

Run: `cd api-gateway-service && ./gradlew test --tests pro.masterdoc.gateway.ProductRolesTest --tests pro.masterdoc.gateway.UserStateMapperTest`

- [ ] **Step 3: Implement**

```kotlin
// ProductRoles.kt
object ProductRoles {
    val ALL: Set<String> =
        setOf("admin", "dispatcher", "engineer", "requester", "reporter", "technologist")

    /** @return error message or null if valid */
    fun validate(roles: List<String>): String? {
        if (roles.isEmpty()) return "roles must not be empty"
        for (r in roles) {
            if (r !in ALL) return "Unknown role: $r"
        }
        return null
    }
}

// UserStateMapper.kt
object UserStateMapper {
    fun fromZitadel(state: String?): String =
        when (state) {
            "USER_STATE_INITIAL" -> "invited"
            "USER_STATE_ACTIVE" -> "active"
            "USER_STATE_INACTIVE", "USER_STATE_LOCKED", "USER_STATE_DELETED" -> "inactive"
            else -> "inactive"
        }
}
```

Note: If live Zitadel uses a different “invited” state string, adjust mapper + tests after one live probe; keep mapping centralized here.

- [ ] **Step 4: Run tests — expect PASS**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pro/masterdoc/gateway/ProductRoles.kt \
        src/main/kotlin/pro/masterdoc/gateway/UserStateMapper.kt \
        src/test/kotlin/pro/masterdoc/gateway/ProductRolesTest.kt \
        src/test/kotlin/pro/masterdoc/gateway/UserStateMapperTest.kt
git commit -m "feat: product roles validation and Zitadel user state mapping"
```

---

### Task 3: Admin DTOs + ZitadelAdminClient interface (fake)

**Files:**
- Create: `api-gateway-service/src/main/kotlin/pro/masterdoc/gateway/AdminUserModels.kt`
- Create: `api-gateway-service/src/main/kotlin/pro/masterdoc/gateway/ZitadelAdminClient.kt`
- Modify: `api-gateway-service/src/main/kotlin/pro/masterdoc/gateway/GatewayConfig.kt`
- Modify: `api-gateway-service/src/main/kotlin/pro/masterdoc/gateway/Application.kt` (`GatewayDeps` only in this task — routes later)

**Interfaces:**
- Produces:

```kotlin
@Serializable
data class InviteUserRequest(
    val email: String,
    val givenName: String,
    val familyName: String,
    val roles: List<String>,
)

@Serializable
data class SetRolesRequest(val roles: List<String>)

@Serializable
data class AdminUser(
    val id: String,
    val email: String,
    val givenName: String,
    val familyName: String,
    val roles: List<String>,
    val state: String,
    val inviteSent: Boolean? = null,
)

@Serializable
data class AdminUserList(val items: List<AdminUser>, val total: Int)

sealed class ZitadelAdminException(message: String) : Exception(message) {
    class Conflict(message: String) : ZitadelAdminException(message)
    class NotFound(message: String) : ZitadelAdminException(message)
    class BadRequest(message: String) : ZitadelAdminException(message)
    class Upstream(message: String) : ZitadelAdminException(message)
}

interface ZitadelAdminClient {
    suspend fun inviteUser(request: InviteUserRequest): AdminUser
    suspend fun listUsers(limit: Int, offset: Int): AdminUserList
    suspend fun setRoles(userId: String, roles: List<String>): AdminUser
    suspend fun resendInvite(userId: String)
}
```

- Fake for tests: in-memory map; `inviteUser` throws `Conflict` if email exists; `resendInvite` throws `Conflict` if state is `active`.

- Config fields: `zitadelOrgId: String`, `zitadelProjectId: String`, `zitadelMgmtToken: String` (empty allowed in testDefaults).

- [ ] **Step 1: Add models + interface + `FakeZitadelAdminClient` in `ZitadelAdminClient.kt`**

Implement fake with mutable state sufficient for Task 5 route tests.

- [ ] **Step 2: Extend `GatewayConfig` / `testDefaults()` / `fromEnv()`**

```kotlin
zitadelOrgId = System.getenv("ZITADEL_ORG_ID") ?: "",
zitadelProjectId = System.getenv("ZITADEL_PROJECT_ID") ?: "",
zitadelMgmtToken = System.getenv("ZITADEL_MGMT_TOKEN") ?: "",
```

- [ ] **Step 3: Extend `GatewayDeps`**

```kotlin
data class GatewayDeps(
    ...
    val zitadelAdminClient: ZitadelAdminClient =
        ZitadelAdminClient { throw UpstreamUnavailableException("zitadel admin not configured") },
)
```

Do **not** wire HTTP live client yet (Task 4). Update existing tests that construct `GatewayDeps` to pass a no-op/fake if the new param is required without default — prefer default fake that throws on use so old tests compile unchanged.

- [ ] **Step 4: Compile tests**

Run: `./gradlew test`  
Expected: existing tests still PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pro/masterdoc/gateway/AdminUserModels.kt \
        src/main/kotlin/pro/masterdoc/gateway/ZitadelAdminClient.kt \
        src/main/kotlin/pro/masterdoc/gateway/GatewayConfig.kt \
        src/main/kotlin/pro/masterdoc/gateway/Application.kt
git commit -m "feat: admin user DTOs and ZitadelAdminClient port"
```

---

### Task 4: HttpZitadelAdminClient

**Files:**
- Modify: `api-gateway-service/src/main/kotlin/pro/masterdoc/gateway/ZitadelAdminClient.kt` (add `HttpZitadelAdminClient`)
- Modify: `Application.kt` `GatewayDeps.live`

**Interfaces:**
- Consumes: `GatewayConfig.zitadelIssuer`, `zitadelOrgId`, `zitadelProjectId`, `zitadelMgmtToken`
- Produces: `ZitadelAdminClient.http(config): ZitadelAdminClient`

**Zitadel HTTP (org header `x-zitadel-orgid` + `Authorization: Bearer <MGMT_TOKEN>`):**

| Method | Call |
|--------|------|
| invite | `POST {issuer}/v2/users/human` with profile + email + `verificationCode` / invite flags per [Invite user](https://zitadel.com/docs/guides/integrate/onboarding/end-users); then `POST /management/v1/users/{id}/grants` with `projectId` + `roleKeys`. On duplicate email → `Conflict`. |
| list | `POST /management/v1/users/_search` with offset/limit; for each user load grants via `POST /management/v1/users/grants/_search` filtered by project (or batch). Map to `AdminUser` (omit `inviteSent`). |
| setRoles | Find grant for project; `PUT /management/v1/users/{id}/grants/{grantId}` with full `roleKeys`; if no grant, `POST` create. Return refreshed user. |
| resend | `POST /v2/users/{id}/invite_code` or Management resend initialization — if user `active`, throw `Conflict`. |

Exact JSON field names: copy from a successful Console/API probe against `auth.fixaverse.ru` while implementing; keep parsing in private DTOs under the same file or `ZitadelAdminDtos.kt`.

Map transport errors: HTTP 409 → `Conflict`, 404 → `NotFound`, 400 → `BadRequest`, network → `Upstream`.

- [ ] **Step 1: Implement `HttpZitadelAdminClient` + factory `ZitadelAdminClient.http(config)`**

- [ ] **Step 2: Wire in `GatewayDeps.live`**

```kotlin
zitadelAdminClient = ZitadelAdminClient.http(config),
```

If `zitadelMgmtToken` blank, use a client that fails with `Upstream("ZITADEL_MGMT_TOKEN not set")` on first call (fail loud in prod misconfig).

- [ ] **Step 3: Unit-test mapping helpers if extracted** (optional small pure functions with table tests). No live network in CI.

- [ ] **Step 4: Commit**

```bash
git commit -am "feat: HTTP Zitadel admin client for invite and grants"
```

---

### Task 5: AdminAuth + AdminUserRoutes (TDD)

**Files:**
- Create: `api-gateway-service/src/main/kotlin/pro/masterdoc/gateway/AdminAuth.kt`
- Create: `api-gateway-service/src/main/kotlin/pro/masterdoc/gateway/AdminUserRoutes.kt`
- Create: `api-gateway-service/src/test/kotlin/pro/masterdoc/gateway/AdminUserRoutesTest.kt`
- Modify: `Application.kt` — `installAdminUserRoutes(deps)`

**Interfaces:**
- `suspend fun ApplicationCall.requireUserInvite(deps): Boolean` — validates JWT; calls `featureClient.getMe`; if features JSON lacks `user_invite`, respond 403 and return false; on invalid token respond 401 and return false.
- Routes as in spec.

- [ ] **Step 1: Write failing route tests** (use `FakeZitadelAdminClient` + accepting/rejecting token validator + feature client returning JSON with/without `user_invite`)

Minimum cases:

1. `POST /admin/users/invites` without Auth → 401  
2. Valid token but features `["board"]` only → 403  
3. Valid + `user_invite` + body → 201 with user id/email/roles/`inviteSent=true`  
4. Unknown role in body → 400  
5. Duplicate email from fake → 409  
6. `GET /admin/users` → 200 items/total  
7. `PUT /admin/users/{id}/roles` empty → 400  
8. `PUT` ok → 200 updated roles  
9. `POST .../resend-invite` on active → 409  
10. `POST .../resend-invite` on invited → 204  

Feature client stub example:

```kotlin
FeatureServiceClient {
    UpstreamResult(
        HttpStatusCode.OK,
        "application/json",
        """{"userInfo":{"id":"a","roles":["admin"]},"features":["user_invite"]}""".toByteArray(),
    )
}
```

- [ ] **Step 2: Run tests — expect FAIL** (routes missing)

Run: `./gradlew test --tests pro.masterdoc.gateway.AdminUserRoutesTest`

- [ ] **Step 3: Implement `AdminAuth` + `installAdminUserRoutes`**

Sketch:

```kotlin
fun Application.installAdminUserRoutes(deps: GatewayDeps) {
    routing {
        post("/admin/users/invites") { ... }
        get("/admin/users") { ... }
        put("/admin/users/{id}/roles") { ... }
        post("/admin/users/{id}/resend-invite") { ... }
    }
}
```

Catch `ZitadelAdminException` → map to status codes. Catch `UpstreamUnavailableException` → 502.

- [ ] **Step 4: Run AdminUserRoutesTest — PASS**

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pro/masterdoc/gateway/AdminAuth.kt \
        src/main/kotlin/pro/masterdoc/gateway/AdminUserRoutes.kt \
        src/test/kotlin/pro/masterdoc/gateway/AdminUserRoutesTest.kt \
        src/main/kotlin/pro/masterdoc/gateway/Application.kt
git commit -m "feat: admin user invite REST routes with user_invite gate"
```

---

### Task 6: OpenAPI + secrets docs

**Files:**
- Modify: `api-gateway-service/openapi.yaml`
- Modify: `api-gateway-service/deploy/.env.example`
- Modify: `api-gateway-service/docs/SECRETS_AND_DOMAINS.md`
- Optionally one line in `docs/AUTH.md` pointing to `/admin/users*`

- [ ] **Step 1: Document paths** `POST /admin/users/invites`, `GET /admin/users`, `PUT /admin/users/{id}/roles`, `POST /admin/users/{id}/resend-invite` with schemas matching DTOs; security Bearer.

- [ ] **Step 2: Env example**

```env
ZITADEL_ORG_ID=
ZITADEL_PROJECT_ID=
ZITADEL_MGMT_TOKEN=
```

Document: PAT must manage users + grants in org; never ship to browser; prefer dedicated machine user (not human admin password).

- [ ] **Step 3: Commit**

```bash
git commit -am "docs: admin invite API and ZITADEL_MGMT_TOKEN secrets"
```

---

### Task 7: Deploy wiring checklist (ops, no code if already patterned)

**Files:** possibly `api-gateway-service/.github/workflows/ci.yml` if it injects env for deploy.

- [ ] **Step 1:** Add GitHub secrets / VPS `/etc/masterdoc-api-gateway/.env`: `ZITADEL_ORG_ID`, `ZITADEL_PROJECT_ID` (`382623622436487171` for current masterdoc-toir), `ZITADEL_MGMT_TOKEN` (PAT of machine user with user/grant rights).

- [ ] **Step 2:** Deploy feature-service (Task 1) then api-gateway so `/me` for admin includes `user_invite` before admin UI calls invite.

- [ ] **Step 3:** Smoke (manual): as admin JWT, `POST /admin/users/invites` with a real mailbox; confirm Zitadel email (requires SMTP). Without SMTP, expect user created but email ops failure — note in runbook.

- [ ] **Step 4:** Commit any workflow/env template changes; do not commit real tokens.

---

## Spec coverage check

| Spec item | Task |
|-----------|------|
| `POST /admin/users/invites` | 5 |
| `GET /admin/users` | 5 |
| `PUT /admin/users/{id}/roles` | 5 |
| `POST .../resend-invite` | 5 |
| Feature `user_invite` + admin mapping | 1, 5 |
| Role validation / empty reject | 2, 5 |
| State mapping | 2, 4 |
| Zitadel BFF + PAT | 3, 4, 7 |
| OpenAPI / secrets | 6, 7 |
| No UI / no SMTP implementation | respected |

## Placeholder scan

None intentional. Live Zitadel invite JSON field names confirmed during Task 4 against running IdP.
