# Hard-delete user Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend `DELETE /admin/users/{id}` to hard-delete any org user except self, and show one «Удалить» button in client-app.

**Architecture:** Gateway route checks `userId != validated.subject` then calls `deleteUser` without an invited-only guard. Client renames `revokeInvite` → `deleteUser`, passes `currentUserId` from `ClientSession`, and shows «Удалить» for everyone except self.

**Tech Stack:** Ktor gateway (Kotlin), Compose Multiplatform client-app, Zitadel Management/User APIs, kotlin.test

## Global Constraints

- Do not run heavy local Gradle/Wasm production builds; push and use CI (workspace rule).
- Targeted unit tests only when needed (`./gradlew test --tests '…'`).
- Org from JWT/`TenantContext` only; no `orgId` in client requests.
- No soft deactivate/reactivate; no confirm dialog.
- Button copy: **«Удалить»** (not «Отозвать»).

---

### Task 1: Fake + client `deleteUser` allows active

**Files:**
- Modify: `api-gateway-service/src/main/kotlin/pro/masterdoc/gateway/ZitadelAdminClient.kt` (`FakeZitadelAdminClient.deleteUser`)
- Modify: `api-gateway-service/src/test/kotlin/pro/masterdoc/gateway/FakeZitadelAdminClientDeleteTest.kt`
- Test: same test file

**Interfaces:**
- Consumes: existing `FakeZitadelAdminClient`, `ZitadelAdminException`
- Produces: `deleteUser` removes invited **and** active; unknown → `NotFound`

- [ ] **Step 1: Update failing/adjusted tests**

Replace invited-only active test with active-delete success:

```kotlin
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
```

- [ ] **Step 2: Run tests — expect active delete to fail until Fake updated**

```bash
./gradlew test --tests 'pro.masterdoc.gateway.FakeZitadelAdminClientDeleteTest'
```

Expected: `deleteUser removes active user` FAIL (Conflict) until Step 3.

- [ ] **Step 3: Relax Fake `deleteUser`**

In `FakeZitadelAdminClient.deleteUser`, remove the `state != "invited"` Conflict check; keep NotFound when missing; remove user (+ grants) for any state.

- [ ] **Step 4: Re-run tests — PASS**

```bash
./gradlew test --tests 'pro.masterdoc.gateway.FakeZitadelAdminClientDeleteTest'
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pro/masterdoc/gateway/ZitadelAdminClient.kt \
  src/test/kotlin/pro/masterdoc/gateway/FakeZitadelAdminClientDeleteTest.kt
git commit -m "fix: FakeZitadelAdminClient.deleteUser allows active users"
```

---

### Task 2: HTTP `deleteUser` + route self-guard

**Files:**
- Modify: `api-gateway-service/src/main/kotlin/pro/masterdoc/gateway/ZitadelAdminClient.kt` (`HttpZitadelAdminClient.deleteUser`)
- Modify: `api-gateway-service/src/main/kotlin/pro/masterdoc/gateway/AdminUserRoutes.kt` (`delete` handler)
- Modify: `api-gateway-service/src/test/kotlin/pro/masterdoc/gateway/AdminUserRoutesTest.kt`
- Modify: `api-gateway-service/openapi.yaml`
- Test: `AdminUserRoutesTest`

**Interfaces:**
- Consumes: `ValidatedToken.subject`, `TenantContext`, `deleteUser(userId)`
- Produces: DELETE self → `409`; DELETE other active/invited → `204`

- [ ] **Step 1: Route tests**

Update `DELETE active user returns 409` → expect `204` and empty list.

Add:

```kotlin
@Test
fun `DELETE self returns 409`() = testApplication {
    val fake = FakeZitadelAdminClient()
    application {
        module(
            GatewayConfig.testDefaults(),
            testDeps(
                featureClientWith("user_invite"),
                fake,
                tokenValidator = TokenValidator.accepting(subject = "self-user", orgId = "test-org"),
            ),
        )
    }
    fake.seed(
        AdminUser(
            id = "self-user",
            email = "self@company.ru",
            givenName = "Self",
            familyName = "User",
            features = listOf("user_invite"),
            state = "active",
        ),
    )
    val deleteResponse =
        client.delete("/admin/users/self-user") {
            header(HttpHeaders.Authorization, "Bearer good-token")
        }
    assertEquals(HttpStatusCode.Conflict, deleteResponse.status)
}
```

Ensure `testDeps` accepts optional `tokenValidator` (already does in this file).

- [ ] **Step 2: Run — expect active DELETE test FAIL (still 409 from Fake/HTTP guard) until impl**

```bash
./gradlew test --tests 'pro.masterdoc.gateway.AdminUserRoutesTest.DELETE*active*' --tests 'pro.masterdoc.gateway.AdminUserRoutesTest.DELETE*self*'
```

- [ ] **Step 3: Implement**

`HttpZitadelAdminClient.deleteUser`: remove invited-only Conflict; keep findUser NotFound; then Management delete (existing path).

`AdminUserRoutes` `delete("/admin/users/{id}")`: after resolving `userId` / `validated`, if `userId == validated.subject` respond `409` `"cannot delete yourself"` and return; else existing `deleteUser` + `204`.

OpenAPI: update DELETE summary/description for hard-delete of org user; mention self → 409.

- [ ] **Step 4: Run AdminUserRoutesTest delete-related — PASS**

```bash
./gradlew test --tests 'pro.masterdoc.gateway.AdminUserRoutesTest' --tests 'pro.masterdoc.gateway.FakeZitadelAdminClientDeleteTest'
```

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/pro/masterdoc/gateway/ZitadelAdminClient.kt \
  src/main/kotlin/pro/masterdoc/gateway/AdminUserRoutes.kt \
  src/test/kotlin/pro/masterdoc/gateway/AdminUserRoutesTest.kt \
  openapi.yaml
git commit -m "feat: DELETE /admin/users/{id} deletes any org user except self"
```

---

### Task 3: Client repository `deleteUser`

**Files:**
- Modify: `client-app/auth/src/commonMain/kotlin/pro/masterdoc/client/auth/AdminUserModels.kt`
- Modify: `client-app/auth/src/jvmTest/kotlin/pro/masterdoc/client/auth/AdminUsersRepositoryTest.kt`
- Test: `AdminUsersRepositoryTest`

**Interfaces:**
- Consumes: `GatewayHttpClient.delete`
- Produces: `suspend fun deleteUser(userId: String)` (rename from `revokeInvite`)

- [ ] **Step 1: Rename test to `deleteUser_deletesUser`** and call `repo.deleteUser("u-1")`.

- [ ] **Step 2: Run — FAIL unresolved `deleteUser`**

```bash
./gradlew :auth:jvmTest --tests 'pro.masterdoc.client.auth.AdminUsersRepositoryTest.deleteUser_deletesUser'
```

- [ ] **Step 3: Rename `revokeInvite` → `deleteUser`** (same HTTP DELETE).

- [ ] **Step 4: Run — PASS**

- [ ] **Step 5: Commit**

```bash
git add auth/src/commonMain/kotlin/pro/masterdoc/client/auth/AdminUserModels.kt \
  auth/src/jvmTest/kotlin/pro/masterdoc/client/auth/AdminUsersRepositoryTest.kt
git commit -m "feat: rename revokeInvite to deleteUser"
```

---

### Task 4: UsersScreen «Удалить»

**Files:**
- Modify: `client-app/composeApp/src/commonMain/kotlin/pro/masterdoc/client/ui/screens/UsersScreen.kt`
- Modify: `client-app/composeApp/src/commonMain/kotlin/pro/masterdoc/client/ui/shell/MainShellContent.kt`
- Test: manual smoke after CI deploy (no local Wasm prod build)

**Interfaces:**
- Consumes: `repository.deleteUser`, `currentUserId: String?`
- Produces: «Удалить» for `user.id != currentUserId`

- [ ] **Step 1: Pass `currentUserId = session.user?.id` into `UsersScreen`**

```kotlin
UsersScreen(
    repository = adminUsersRepository,
    currentUserId = session.user?.id,
)
```

- [ ] **Step 2: UI**

- Add param `currentUserId: String? = null`
- Rename `revokingId` → `deletingId`
- Show button when `currentUserId == null || user.id != currentUserId`
- Label: «Удалить» / «…»
- Call `repository.deleteUser(user.id)`
- Extend `AdminUserAction` with `Delete` (replace `Revoke`)
- 409 for Delete → `"Нельзя удалить себя"`

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/pro/masterdoc/client/ui/screens/UsersScreen.kt \
  composeApp/src/commonMain/kotlin/pro/masterdoc/client/ui/shell/MainShellContent.kt
git commit -m "feat: UsersScreen Удалить for all users except self"
```

- [ ] **Step 4: Push client-app + gateway; watch CI; smoke on prod**

```bash
git push origin HEAD
gh run watch <id> --exit-status
```

Smoke: login → Пользователи → list; «Удалить» on other user (invite throwaway); no button on self; active delete works; self API 409 if forced.

---

## Self-review

1. **Spec coverage:** Gateway DELETE semantics, self-guard, OpenAPI, client rename, UI button, tests — Tasks 1–4.
2. **Placeholders:** none.
3. **Types:** `deleteUser(userId: String)` consistent across Fake/HTTP/route/client.
