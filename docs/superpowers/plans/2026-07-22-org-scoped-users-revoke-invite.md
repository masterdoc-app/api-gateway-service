# Org-scoped users + revoke invite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Scope `/admin/users*` to the caller's Zitadel org from JWT, and let admins revoke invitations by hard-deleting `invited` users — including client-app UI.

**Architecture:** Finish ambient `TenantContext` (existing plan) so Management calls use `x-zitadel-orgid` from JWT. Add `DELETE /admin/users/{id}` that only deletes when `UserStateMapper` → `invited`. Client adds `GatewayHttpClient.delete`, `AdminUsersRepository.revokeInvite`, and «Отозвать» on `UsersScreen`.

**Tech Stack:** Kotlin, Ktor (gateway), Compose Multiplatform (client-app), JUnit/kotlin.test, Zitadel Management/v2 APIs.

**Spec:** `docs/superpowers/specs/2026-07-22-org-scoped-users-revoke-invite-design.md`  
**Tenant prerequisite plan:** `docs/superpowers/plans/2026-07-20-tenant-context-admin-org.md`

## Global Constraints

- Org only from JWT claim `urn:zitadel:iam:org:id` — never from body/query/`X-Org-Id`.
- Handler and `ZitadelAdminClient` method signatures must not take `orgId`.
- Revoke = hard delete; only when mapped state is `invited`; otherwise `409`.
- No confirmation dialog in client v1.
- Targeted tests only (no local Wasm/Docker/full monorepo builds). Prefer `./gradlew test --tests '…'` then push for CI.
- Gateway branch: `feat/tenant-context-admin-org`. Client-app: feature branch from its `main` (e.g. `feat/revoke-invite`).
- Repos: `api-gateway-service` and sibling `client-app` under Formaverse.

## File map

| File | Role |
|------|------|
| (tenant plan files) | JWT org → `TenantContext` → `x-zitadel-orgid` |
| `api-gateway-service/.../ZitadelAdminClient.kt` | `deleteUser`; HTTP DELETE to Zitadel; Fake impl |
| `api-gateway-service/.../AdminUserRoutes.kt` | `DELETE /admin/users/{id}` |
| `api-gateway-service/openapi.yaml` | Document DELETE |
| `api-gateway-service/.../AdminUserRoutesTest.kt` | invited 204 / active 409 |
| `client-app/.../GatewayHttpClient.kt` | `delete(...)` |
| `client-app/.../JvmGatewayHttpClient.kt` (+ wasm/android actuals) | Implement DELETE |
| `client-app/.../AdminUserModels.kt` | `revokeInvite` |
| `client-app/.../AdminUsersRepositoryTest.kt` | Recording DELETE test |
| `client-app/.../UsersScreen.kt` | «Отозвать» for `invited` |

---

## Phase 0: Tenant context (do first)

- [ ] **Step 0:** Execute **all** tasks in `docs/superpowers/plans/2026-07-20-tenant-context-admin-org.md` (TenantContext, ValidatedToken, AdminAuth `withTenant`, header from context, drop `ZITADEL_ORG_ID`).
- [ ] While touching `AdminAuth.kt`, remove any leftover WIP (`requireFeature`, hard-coded `OrgIdKey` / `AuthHeaderKey` stubs) that is not part of that plan — keep a single clean `requireUserInvite` → `ValidatedToken?` path.
- [ ] Confirm gateway unit tests for tenant pass before starting Task 1 below.

---

### Task 1: `ZitadelAdminClient.deleteUser` (gateway)

**Files:**
- Modify: `src/main/kotlin/pro/masterdoc/gateway/ZitadelAdminClient.kt`
- Modify: any `RecordingOrgAdminClient` / fakes in tests that implement the interface

**Interfaces:**
- Consumes: `TenantContext` (via existing `applyAuthHeaders`), `UserStateMapper`, `findUser`
- Produces: `suspend fun deleteUser(userId: String)` on `ZitadelAdminClient`

- [ ] **Step 1: Write failing Fake + interface-driven unit tests**

Add to `src/test/kotlin/pro/masterdoc/gateway/FakeZitadelAdminClientDeleteTest.kt` (or extend an existing Fake test class):

```kotlin
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
                InviteUserRequest("a@b.com", "A", "B", listOf("technologist")),
            )
        fake.deleteUser(created.id)
        assertEquals(0, fake.listUsers(50, 0).total)
    }

    @Test
    fun `deleteUser on active throws Conflict`() = runBlocking {
        val fake = FakeZitadelAdminClient()
        val created =
            fake.inviteUser(
                InviteUserRequest("a@b.com", "A", "B", listOf("technologist")),
            )
        fake.markActive(created.id)
        assertFailsWith<ZitadelAdminException.Conflict> {
            fake.deleteUser(created.id)
        }
    }

    @Test
    fun `deleteUser unknown throws NotFound`() = runBlocking {
        val fake = FakeZitadelAdminClient()
        assertFailsWith<ZitadelAdminException.NotFound> {
            fake.deleteUser("missing")
        }
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (method missing)

```bash
cd api-gateway-service
./gradlew test --tests 'pro.masterdoc.gateway.FakeZitadelAdminClientDeleteTest'
```

Expected: compile error / missing `deleteUser`.

- [ ] **Step 3: Implement port + Fake + HTTP**

On `ZitadelAdminClient`:

```kotlin
suspend fun deleteUser(userId: String)
```

Add the method to `unconfigured()` / `notConfiguredClient` stubs (`fail()`).

`FakeZitadelAdminClient`:

```kotlin
override suspend fun deleteUser(userId: String) {
    val user = usersById[userId] ?: throw ZitadelAdminException.NotFound("user not found")
    if (user.state != "invited") {
        throw ZitadelAdminException.Conflict("only invited users can be revoked")
    }
    usersById.remove(userId)
    idByEmail.remove(user.email.lowercase())
}
```

`HttpZitadelAdminClient`:

```kotlin
override suspend fun deleteUser(userId: String) {
    val user = findUser(userId) ?: throw ZitadelAdminException.NotFound("user not found")
    if (UserStateMapper.fromZitadel(user.state, user.human?.email?.isEmailVerified) != "invited") {
        throw ZitadelAdminException.Conflict("only invited users can be revoked")
    }
    val response = deleteJson("$baseUrl/v2/users/$userId")
    ensureSuccess(response)
}
```

Add private `deleteJson` mirroring `postJson`/`putJson` using `client.delete(url) { applyAuthHeaders(); … }` (Ktor client `delete`).

If Task 3 of the tenant plan introduced `RecordingOrgAdminClient`, add:

```kotlin
override suspend fun deleteUser(userId: String) {
    seenOrgIds += TenantContext.requireOrgId()
}
```

- [ ] **Step 4: Run Fake tests — expect PASS**

```bash
./gradlew test --tests 'pro.masterdoc.gateway.FakeZitadelAdminClientDeleteTest'
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pro/masterdoc/gateway/ZitadelAdminClient.kt \
  src/test/kotlin/pro/masterdoc/gateway/FakeZitadelAdminClientDeleteTest.kt \
  src/test/kotlin/pro/masterdoc/gateway/AdminUserRoutesTest.kt
git commit -m "feat: ZitadelAdminClient.deleteUser for invited revoke"
```

---

### Task 2: `DELETE /admin/users/{id}` route + OpenAPI

**Files:**
- Modify: `src/main/kotlin/pro/masterdoc/gateway/AdminUserRoutes.kt`
- Modify: `src/test/kotlin/pro/masterdoc/gateway/AdminUserRoutesTest.kt`
- Modify: `openapi.yaml`

**Interfaces:**
- Consumes: `requireUserInvite` → `ValidatedToken?`, `TenantContext.withTenant`, `deleteUser`
- Produces: HTTP `DELETE /admin/users/{id}` → `204` / `401` / `403` / `404` / `409` / `502`

- [ ] **Step 1: Write failing route tests**

```kotlin
@Test
fun `DELETE invited user returns 204 and removes from list`() = testApplication {
    val fake = FakeZitadelAdminClient()
    application {
        module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite"), fake))
    }
    val inviteResponse =
        client.post("/admin/users/invites") {
            header(HttpHeaders.Authorization, "Bearer good-token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                """{"email":"invited@company.ru","givenName":"I","familyName":"N","roles":["technologist"]}""",
            )
        }
    val userId =
        Json.parseToJsonElement(inviteResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

    val deleteResponse =
        client.delete("/admin/users/$userId") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }
    assertEquals(HttpStatusCode.NoContent, deleteResponse.status)

    val listResponse =
        client.get("/admin/users") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }
    val total =
        Json.parseToJsonElement(listResponse.bodyAsText()).jsonObject["total"]!!.jsonPrimitive.content.toInt()
    assertEquals(0, total)
}

@Test
fun `DELETE active user returns 409`() = testApplication {
    val fake = FakeZitadelAdminClient()
    application {
        module(GatewayConfig.testDefaults(), testDeps(featureClientWith("user_invite"), fake))
    }
    val inviteResponse =
        client.post("/admin/users/invites") {
            header(HttpHeaders.Authorization, "Bearer good-token")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(
                """{"email":"active@company.ru","givenName":"A","familyName":"C","roles":["technologist"]}""",
            )
        }
    val userId =
        Json.parseToJsonElement(inviteResponse.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
    fake.markActive(userId)

    val deleteResponse =
        client.delete("/admin/users/$userId") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }
    assertEquals(HttpStatusCode.Conflict, deleteResponse.status)
}
```

Add imports: `io.ktor.client.request.delete`.

- [ ] **Step 2: Run — expect FAIL** (404 route missing)

```bash
./gradlew test --tests 'pro.masterdoc.gateway.AdminUserRoutesTest'
```

- [ ] **Step 3: Implement route**

In `AdminUserRoutes.kt` (after tenant Phase 0, `requireUserInvite` returns `ValidatedToken?`):

```kotlin
import io.ktor.server.routing.delete

delete("/admin/users/{id}") {
    val validated = call.requireUserInvite(deps) ?: return@delete
    val userId = call.parameters["id"] ?: run {
        call.respondText("Bad Request", status = HttpStatusCode.BadRequest)
        return@delete
    }
    try {
        TenantContext.withTenant(validated.orgId) {
            deps.zitadelAdminClient.deleteUser(userId)
        }
        call.respondText("", status = HttpStatusCode.NoContent)
    } catch (e: ZitadelAdminException) {
        call.respondZitadelAdminException(e)
    } catch (_: UpstreamUnavailableException) {
        call.respondText("Bad Gateway", status = HttpStatusCode.BadGateway)
    }
}
```

OpenAPI — after `/admin/users/{id}/resend-invite`, add:

```yaml
  /admin/users/{id}:
    delete:
      operationId: deleteAdminUserInvite
      summary: Revoke invitation by deleting an invited user
      description: |
        Hard-deletes the user in Zitadel when state is `invited`.
        Returns 409 if the user is already active (or otherwise not invited).
        Scoped to the caller's org from JWT claim `urn:zitadel:iam:org:id`.
      security:
        - bearerAuth: []
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
      responses:
        "204":
          description: Invitation revoked (user deleted)
        "401":
          description: Missing or invalid JWT / missing org claim
        "403":
          description: Missing `user_invite` feature
        "404":
          description: Unknown user id (plain text)
        "409":
          description: User is not in invited state (plain text)
        "502":
          description: Zitadel Management API unavailable
```

Also update the top OpenAPI bullet list to mention revoke/delete.

- [ ] **Step 4: Run AdminUserRoutesTest — expect PASS**

```bash
./gradlew test --tests 'pro.masterdoc.gateway.AdminUserRoutesTest'
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pro/masterdoc/gateway/AdminUserRoutes.kt \
  src/test/kotlin/pro/masterdoc/gateway/AdminUserRoutesTest.kt \
  openapi.yaml
git commit -m "feat: DELETE /admin/users/{id} to revoke invitations"
```

---

### Task 3: `GatewayHttpClient.delete` (client-app)

**Files:**
- Modify: `client-app/auth/src/commonMain/kotlin/pro/masterdoc/client/auth/GatewayHttpClient.kt`
- Modify: `client-app/auth/src/jvmMain/kotlin/pro/masterdoc/client/auth/JvmGatewayHttpClient.kt`
- Modify: `client-app/auth/src/wasmJsMain/kotlin/pro/masterdoc/client/auth/AuthPlatform.wasmJs.kt` (`WasmGatewayHttpClient`)
- Modify: `client-app/auth/src/androidMain/kotlin/pro/masterdoc/client/auth/AuthPlatform.android.kt`
- Modify: recording fakes in `AdminUsersRepositoryTest.kt` and `AuthRepositoryTest.kt`

**Interfaces:**
- Produces: `suspend fun delete(url: String, headers: Map<String, String> = emptyMap()): GatewayHttpResponse`

- [ ] **Step 1: Extend interface**

```kotlin
interface GatewayHttpClient {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): GatewayHttpResponse
    suspend fun postForm(url: String, body: String, headers: Map<String, String> = emptyMap()): GatewayHttpResponse
    suspend fun postBytes(url: String, body: ByteArray, headers: Map<String, String> = emptyMap()): GatewayHttpResponse
    suspend fun delete(url: String, headers: Map<String, String> = emptyMap()): GatewayHttpResponse
}
```

- [ ] **Step 2: Implement platforms**

`JvmGatewayHttpClient`:

```kotlin
override suspend fun delete(
    url: String,
    headers: Map<String, String>,
): GatewayHttpResponse = execute("DELETE", url, headers, null)
```

`WasmGatewayHttpClient`: mirror existing `get`/`postForm` fetch helpers with `method = "DELETE"` and no body (copy the pattern already used for GET).

Android stub actual:

```kotlin
override suspend fun delete(
    url: String,
    headers: Map<String, String>,
): GatewayHttpResponse = GatewayHttpResponse(501, "HTTP not wired on Android auth actual")
```

Update every test `GatewayHttpClient` fake (`RecordingGatewayHttpClient` in AdminUsers + AuthRepository tests) with:

```kotlin
override suspend fun delete(
    url: String,
    headers: Map<String, String>,
): GatewayHttpResponse = handler("DELETE", url, headers, null)
```

- [ ] **Step 3: Compile auth JVM tests**

```bash
cd client-app
./gradlew :auth:jvmTest --tests 'pro.masterdoc.client.auth.AdminUsersRepositoryTest'
```

Expected: PASS (existing tests; interface complete). If CI-only policy: push after Task 5 instead of local heavy runs — still preferred to run `:auth:jvmTest` (targeted).

- [ ] **Step 4: Commit (in client-app repo)**

```bash
git checkout -b feat/revoke-invite
git add auth/src/commonMain/kotlin/pro/masterdoc/client/auth/GatewayHttpClient.kt \
  auth/src/jvmMain/kotlin/pro/masterdoc/client/auth/JvmGatewayHttpClient.kt \
  auth/src/wasmJsMain/kotlin/pro/masterdoc/client/auth/AuthPlatform.wasmJs.kt \
  auth/src/androidMain/kotlin/pro/masterdoc/client/auth/AuthPlatform.android.kt \
  auth/src/jvmTest/kotlin/pro/masterdoc/client/auth/AdminUsersRepositoryTest.kt \
  auth/src/jvmTest/kotlin/pro/masterdoc/client/auth/AuthRepositoryTest.kt
git commit -m "feat: add GatewayHttpClient.delete for admin revoke"
```

---

### Task 4: `AdminUsersRepository.revokeInvite` (client-app)

**Files:**
- Modify: `client-app/auth/src/commonMain/kotlin/pro/masterdoc/client/auth/AdminUserModels.kt`
- Modify: `client-app/auth/src/jvmTest/kotlin/pro/masterdoc/client/auth/AdminUsersRepositoryTest.kt`

**Interfaces:**
- Consumes: `GatewayHttpClient.delete`
- Produces: `suspend fun revokeInvite(userId: String)` — success on HTTP 204

- [ ] **Step 1: Write failing test**

```kotlin
@Test
fun revokeInvite_deletesUser() =
    runBlocking {
        val tokens = InMemoryTokenStore()
        tokens.write(AuthTokens(accessToken = "at"))
        val http =
            RecordingGatewayHttpClient { method, url, headers, body ->
                assertEquals("DELETE", method)
                assertTrue(url.endsWith("/admin/users/u-1"))
                assertEquals("Bearer at", headers["Authorization"])
                assertEquals(null, body)
                GatewayHttpResponse(204, "")
            }
        val repo =
            AdminUsersRepository(
                config = AuthConfig(clientId = "web"),
                http = http,
                tokenStore = tokens,
            )
        repo.revokeInvite("u-1")
    }
```

- [ ] **Step 2: Run — expect FAIL**

```bash
./gradlew :auth:jvmTest --tests 'pro.masterdoc.client.auth.AdminUsersRepositoryTest.revokeInvite_deletesUser'
```

- [ ] **Step 3: Implement**

```kotlin
suspend fun revokeInvite(userId: String) {
    val access =
        tokenStore.read()?.accessToken
            ?: throw GatewayHttpException(401, "Not authenticated")
    val response =
        http.delete(
            url = "${config.gatewayBaseUrl.trimEnd('/')}/admin/users/$userId",
            headers = mapOf("Authorization" to "Bearer $access"),
        )
    if (!response.isSuccessful) {
        throw GatewayHttpException(response.status, "DELETE /admin/users/$userId failed: ${response.body}")
    }
}
```

- [ ] **Step 4: Run test — expect PASS**

```bash
./gradlew :auth:jvmTest --tests 'pro.masterdoc.client.auth.AdminUsersRepositoryTest'
```

- [ ] **Step 5: Commit**

```bash
git add auth/src/commonMain/kotlin/pro/masterdoc/client/auth/AdminUserModels.kt \
  auth/src/jvmTest/kotlin/pro/masterdoc/client/auth/AdminUsersRepositoryTest.kt
git commit -m "feat: AdminUsersRepository.revokeInvite via DELETE"
```

---

### Task 5: UsersScreen «Отозвать» (client-app)

**Files:**
- Modify: `client-app/composeApp/src/commonMain/kotlin/pro/masterdoc/client/ui/screens/UsersScreen.kt`

**Interfaces:**
- Consumes: `repository.revokeInvite(userId)`

- [ ] **Step 1: Add revoke UI state + row action**

Replace the list rendering block so each user row shows status and, when `user.state == "invited"`, an «Отозвать» control:

```kotlin
var revokingId by remember { mutableStateOf<String?>(null) }

// inside users.forEach { user ->
Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
) {
    Column(modifier = Modifier.weight(1f)) {
        AppText(text = "${user.givenName} ${user.familyName} · ${user.email}")
        AppText(
            text =
                user.features
                    .map { id -> titleById[id] ?: id }
                    .joinToString(", ")
                    .ifEmpty { "-" },
        )
        AppText(text = user.state)
    }
    if (user.state == "invited") {
        AppButton(
            text = if (revokingId == user.id) "…" else "Отозвать",
            enabled = revokingId == null,
            onClick = {
                scope.launch {
                    revokingId = user.id
                    error = null
                    try {
                        repository.revokeInvite(user.id)
                        users = repository.listUsers().items
                    } catch (e: GatewayHttpException) {
                        error = humanAdminError(e)
                    } catch (e: Exception) {
                        error = e.message ?: "Ошибка отзыва"
                    } finally {
                        revokingId = null
                    }
                }
            },
        )
    }
}
```

Extend `humanAdminError`:

```kotlin
404 -> "Пользователь не найден"
409 -> "Можно отозвать только приглашение"
```

(Keep existing 400/403/409-email/502 mappings; if 409 was only used for duplicate email on invite, prefer a shared message or branch on context — simplest: use the revoke-specific string for all 409 in this screen, or `"Конфликт: ${e.message.ifBlank { "операция недоступна" }}"`.)

- [ ] **Step 2: Manual smoke after deploy** (no local Wasm production build): invite a throwaway user → see «Отозвать» → click → row gone; active user has no button.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/pro/masterdoc/client/ui/screens/UsersScreen.kt
git commit -m "feat: revoke invite action on UsersScreen"
```

---

### Task 6: Push + CI

- [ ] **Gateway:** from `api-gateway-service` on `feat/tenant-context-admin-org`:

```bash
git push -u origin HEAD
gh run list --branch feat/tenant-context-admin-org
```

- [ ] **Client:** from `client-app` on `feat/revoke-invite`:

```bash
git push -u origin HEAD
gh run list --branch feat/revoke-invite
```

- [ ] Fix CI failures if any; do not run heavy local Wasm/Docker builds.

---

## Self-review (plan vs spec)

| Spec item | Task |
|-----------|------|
| JWT org / TenantContext | Phase 0 (existing tenant plan) |
| List only own org | Phase 0 |
| DELETE invited only | Tasks 1–2 |
| Hard delete / gone from list | Tasks 1–2 |
| 401/403/404/409/502 | Task 2 + OpenAPI |
| client revokeInvite | Tasks 3–4 |
| UsersScreen «Отозвать» | Task 5 |
| No org in request | Phase 0 + Global Constraints |
| No confirm dialog | Task 5 |

No TBD placeholders. Signatures aligned: `deleteUser(userId)`, `revokeInvite(userId)`, `GatewayHttpClient.delete`.
