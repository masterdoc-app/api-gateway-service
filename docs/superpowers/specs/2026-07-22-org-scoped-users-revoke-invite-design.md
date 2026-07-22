# Org-scoped users list + revoke invite

Date: 2026-07-22  
Status: approved (conversation)  
Scope: **api-gateway-service** + **client-app** (`UsersScreen` / `AdminUsersRepository`)

## Goal

1. Admin user list (and other `/admin/users*`) operate only on the **caller's Zitadel organization** (JWT claim `urn:zitadel:iam:org:id`) — “своя компания”.
2. Allow **revoking an invitation** by hard-deleting an `invited` user; no block action for `active` users.

## Non-goals

- Soft deactivate / `inactive` lifecycle / unlock
- Confirmation dialog before revoke
- Postgres/`org_id` multi-tenancy in other services
- Changing feature-service `/me` contract
- Client sending `orgId` / `X-Org-Id`

## Relationship to prior design

Org scoping follows **`2026-07-20-tenant-context-admin-org-design.md`** (ambient `TenantContext`, `ValidatedToken.orgId`, `x-zitadel-orgid` from context, drop runtime `ZITADEL_ORG_ID` for admin calls). This document adds revoke-invite and client UX; it does not re-litigate tenant mechanics.

## Decisions (from brainstorming)

| Topic | Choice |
|-------|--------|
| Revoke target | **Only `invited`** users |
| Revoke effect | **Hard delete** in Zitadel (user disappears from list) |
| Org meaning | Zitadel org from JWT (`TenantContext`) |
| Delivery | Gateway API + OpenAPI + **client-app** UI |

## Design

### 1. Gateway — tenant (summary)

- Validate JWT → `ValidatedToken(subject, orgId)`; missing/blank org → `401` on admin paths.
- After `user_invite` check, run Zitadel work inside `TenantContext.withTenant(orgId)`.
- `HttpZitadelAdminClient` sets `x-zitadel-orgid` from `TenantContext.requireOrgId()`.
- Handlers and `ZitadelAdminClient` method signatures stay free of `orgId` parameters.

### 2. Gateway — revoke invite

**Endpoint:** `DELETE /admin/users/{id}`  
**Auth:** same as other admin user routes (`requireUserInvite` + tenant).

**Behavior:**

1. Resolve user in the caller's org (via existing Management API + org header).
2. Map state with `UserStateMapper`. If state ≠ `invited` → `409` with plain-text reason (e.g. only invitations can be revoked).
3. If `invited` → hard-delete user in Zitadel (Management/User API delete).
4. Success → `204 No Content`.

**Port:** add `suspend fun deleteUser(userId: String)` (or `revokeInvite`) on `ZitadelAdminClient`; implement on HTTP + `FakeZitadelAdminClient`.

### 3. Errors

| Case | HTTP |
|------|------|
| No/invalid JWT or missing org claim | `401` |
| No `user_invite` | `403` |
| Unknown user / not visible in caller's org | `404` |
| Delete when state is not `invited` | `409` |
| feature-service / Zitadel unavailable | `502` |

### 4. OpenAPI

- Document `DELETE /admin/users/{id}` (operationId e.g. `deleteAdminUser` / `revokeAdminUserInvite`).
- Note: only valid for `state=invited`; otherwise `409`.
- List/invite descriptions: scoped to caller's org from JWT.

### 5. client-app

- `AdminUsersRepository.revokeInvite(userId: String)` → `DELETE /admin/users/{id}` with Bearer token; treat `204` as success.
- `UsersScreen`: for each user with `state == "invited"`, show text action **«Отозвать»** next to the status; hide for `active`.
- On click: call revoke (disable that row’s button while in flight); on success reload list; on failure map via existing `humanAdminError` (extend for `404` message if useful).
- No confirmation dialog in v1.

### 6. Testing

**Gateway**

- Org A vs org B: list/invite/delete see only own org (tenant tests from prior plan).
- Delete invited → `204`; user absent from subsequent list.
- Delete active → `409`.
- Missing org claim → `401`.

**Client**

- Repository test: revoke hits `DELETE /admin/users/{id}` and accepts `204`.

## Out of follow-up

- Restore / reactivate deleted users  
- Soft `inactive` block for active users  
- Confirmation modal  
- feature-service `orgId` on `/me`

## Success criteria

- Two callers with different JWT org claims do not see each other's users.
- Invited user can be removed via DELETE; active user cannot.
- client-app list shows «Отозвать» only for `invited` and removes the row after success.
- Admin handlers / client method signatures do not take `orgId` from the request.
