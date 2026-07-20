# Tenant context — org from JWT for admin APIs

Date: 2026-07-20  
Status: approved (conversation)  
Scope: **api-gateway-service** — ambient tenant for `/admin/users*`; org from caller JWT

## Goal

Each organization operates against its own Zitadel org without `orgId` appearing in handler / admin client method signatures. Admin invite/list/roles/resend use the **caller's** org from the access token.

## Non-goals

- Client sending `orgId` / `X-Org-Id` (untrusted)
- Schema/DB multi-tenancy in backend (later; same `TenantContext` pattern)
- Creating Zitadel Organizations via API
- Sharing a published `tenant-context` library across repos in this change (keep types in gateway; extract later if needed)
- Changing feature-service `/me` contract (optional `orgId` in response is out of scope)

## Problem today

`HttpZitadelAdminClient` sets `x-zitadel-orgid` from env `ZITADEL_ORG_ID` (platform/single org). Multi-org SaaS cannot invite into the caller's organization.

## Decision

**Ambient `TenantContext`** populated once per authenticated admin request from JWT claim `urn:zitadel:iam:org:id`. Downstream Zitadel Management calls read `TenantContext.requireOrgId()`.

## Design

### 1. Validated token

Extend token validation beyond `sub`:

```kotlin
data class ValidatedToken(
    val subject: String,
    val orgId: String,
)
```

- Verify signature + issuer (existing JWKS path).
- Read claim `urn:zitadel:iam:org:id` as non-blank `String`.
- Missing / blank org claim → treat as invalid token for admin paths (`401`).
- `TokenValidator.validate` returns `ValidatedToken?` (update fakes/tests accordingly).  
  Call sites that only need auth (`GET /me`) may keep using `validated?.subject` or ignore `orgId`.

### 2. TenantContext

Small gateway-local helper (no DI required):

| API | Behavior |
|-----|----------|
| `withTenant(orgId) { … }` | Sets current org for the block (coroutine-safe: `ThreadLocal` cleared in `finally`, or `CoroutineContext` element if call chain is suspend-only) |
| `current(): String?` | Current org or null |
| `requireOrgId(): String` | Current org or throw (programming error if client called outside `withTenant`) |

Admin routes enter `withTenant` **after** JWT + `user_invite` checks succeed, wrapping the Zitadel work.

Handlers stay free of `orgId` parameters:

```kotlin
deps.zitadelAdmin.inviteUser(request)
```

### 3. Admin auth (`requireUserInvite`)

Order:

1. Bearer present → validate JWT → `ValidatedToken` (else `401`)
2. `GET` feature-service `/me` with same Authorization → require feature `user_invite` (else `403` / `502` as today)
3. Caller proceeds inside `withTenant(token.orgId)`

Do **not** take org from `/me` body; JWT is source of truth.

### 4. ZitadelAdminClient

- `applyAuthHeaders`: `x-zitadel-orgid` = `TenantContext.requireOrgId()`.
- Stop requiring `GatewayConfig.zitadelOrgId` for constructing the HTTP client.
- Config still needs `zitadelMgmtToken` + issuer; blank MGMT token → unconfigured client (unchanged idea).
- Remove runtime dependency on `ZITADEL_ORG_ID` for admin paths (may leave env parsing as unused/deprecated or delete field — prefer **delete** from `GatewayConfig` + deploy env docs to avoid false sense of tenancy).
- `FakeZitadelAdminClient`: no org param; tests assert behavior via `withTenant` when exercising HTTP header mapping (unit test on header builder / thin wrapper if needed).

### 5. Ops / machine user

PAT (`ZITADEL_MGMT_TOKEN`) must be allowed to manage users/grants **in each client org** (or use an IAM model that permits cross-org management). Document in gateway secrets/runbook: platform `ZITADEL_ORG_ID` remains for **Terraform bootstrap** in `masterdoc-zitadel`, not for per-request admin API scoping.

### 6. Errors

| Case | HTTP |
|------|------|
| No/invalid JWT or missing org claim | `401` |
| No `user_invite` | `403` |
| feature-service down / non-OK | `502` |
| Zitadel conflicts / not found / bad request | existing mapping |
| `requireOrgId()` outside tenant | should not reach HTTP; fail tests / 500 only if bug |

## Test plan

- Unit: JWT with org claim → `ValidatedToken.orgId`; without claim → null/401 path
- Unit: `withTenant` nested/clear; `requireOrgId` outside → throws
- Admin route tests: invite/list under `withTenant` from token; fake/recording client sees org A vs org B when tokens differ (no env org)
- Regression: role validation, 403 without feature, conflict mapping unchanged

## Out of follow-up (explicit)

- Postgres/backend `org_id` column + repository auto-scope
- feature-service exposing `orgId` on `/me`
- Project grant / multi-org login UX in client-app

## Success criteria

- Admin handlers and `ZitadelAdminClient` method signatures have no `orgId` parameter
- Two callers with different JWT org claims operate on different Zitadel orgs
- `ZITADEL_ORG_ID` is not used for `x-zitadel-orgid` on admin Management API calls
