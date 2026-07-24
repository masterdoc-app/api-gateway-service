# Hard-delete user (active + invited)

Date: 2026-07-22  
Status: approved (conversation; user waived per-section review — proceed with recommended A)  
Scope: **api-gateway-service** + **client-app** (`UsersScreen` / `AdminUsersRepository`)

## Goal

Allow an org admin to **hard-delete** any org user (`invited` or `active`) via one UI action **«Удалить»**. Re-invite later if needed. No soft block / deactivate.

## Non-goals

- Soft deactivate / reactivate / `inactive` lifecycle UI
- Confirmation dialog before delete
- Changing org scoping / `TenantContext` / feature-service `/me`
- Separate revoke vs delete endpoints

## Relationship to prior design

Supersedes the “only `invited`” rule in `2026-07-22-org-scoped-users-revoke-invite-design.md` for `DELETE /admin/users/{id}`. Org scoping and `user_invite` gating stay the same.

## Decisions

| Topic | Choice |
|-------|--------|
| Approach | **A** — extend existing `DELETE /admin/users/{id}` |
| UI | One button **«Удалить»** for `active` and `invited` |
| Self-delete | **Forbidden** — `409` if `{id}` equals JWT `sub` |
| `inactive` | Also deletable (same hard delete; no special UI beyond showing status) |
| Re-add user | Invite again |

## Design

### 1. Gateway — `DELETE /admin/users/{id}`

**Auth:** unchanged — `requireUserInvite` + `TenantContext.withTenant(orgId)`.

**Behavior:**

1. If `userId == validated.subject` → `409` plain text (e.g. `cannot delete yourself`).
2. Else call `ZitadelAdminClient.deleteUser(userId)`:
   - Resolve user in caller org (existing `findUser` / org header).
   - Missing → `404`.
   - Present → hard delete in Zitadel (Management `DELETE` / existing delete path).
3. Success → `204 No Content`.

**Client change:** remove the “state must be `invited`” guard inside `deleteUser` (HTTP + Fake). Self-check lives in the **route**, not the Zitadel client (client has no access to JWT `sub`).

### 2. Errors

| Case | HTTP |
|------|------|
| No/invalid JWT or missing org | `401` |
| No `user_invite` | `403` |
| Unknown user / not in org | `404` |
| Delete self | `409` |
| feature-service / Zitadel unavailable / upstream 403 mapped | `502` (existing) |

### 3. OpenAPI

- Update `DELETE /admin/users/{id}` summary/description: delete org user (invited or active), not invite-only; note self-delete → `409`.

### 4. client-app

- Rename repository method `revokeInvite` → `deleteUser` (same `DELETE /admin/users/{id}`).
- `UsersScreen`: pass `currentUserId` from `ClientSession.user?.id` (via `MainShellContent`).
- Show **«Удалить»** when `user.id != currentUserId` (for any listed state).
- Hide button for self; gateway still enforces `409` if called.
- Loading: same row in-flight pattern as today’s revoke (`deletingId`).
- Error mapping: 409 → «Нельзя удалить себя» when delete action (keep invite duplicate message for invite action).

### 5. Testing

**Gateway**

- Fake: `deleteUser` removes active and invited; unknown → NotFound.
- Routes: DELETE active → `204`; DELETE self (`id` == accepting validator subject) → `409`; DELETE invited still `204`.

**Client**

- Repository test: `deleteUser` calls DELETE (rename from `revokeInvite_deletesUser`).
- UI: button label «Удалить»; hidden for current user id.

### 6. Out of scope

- Deactivate / reactivate
- Confirm dialog
- Fixing list-row layout/wrapping (separate if needed)

## Success criteria

- Admin can delete another `active` or `invited` user in their org; row gone after reload.
- Cannot delete self (`409` / no button).
- Org scoping and `user_invite` gating unchanged.
