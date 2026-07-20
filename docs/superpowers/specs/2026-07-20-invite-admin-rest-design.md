# Admin user invite — REST design

Date: 2026-07-20  
Status: approved (conversation)  
Scope: **REST contract only** (no UI, no SMTP ops in this doc)

## Goal

Admin invites users **from the client app**, assigns product roles, and users set a password via Zitadel email. Forgotten password uses Zitadel’s built-in email reset. Later, the same capability may be granted to other roles (e.g. dispatcher, reporter) via a **feature flag**, not by forking APIs.

## Non-goals

- Custom password-reset REST (Login UI / Zitadel handles it)
- Self-signup
- Creating client Organizations
- Admin UI screens
- SMTP configuration (Zitadel ops)

## Architecture

```
Client (admin app)
  → api-gateway  POST/GET/PUT /admin/users*
       → auth: Bearer JWT + feature `user_invite`
       → Zitadel Management API (invite user, project grants)
feature-service
  → maps role `admin` → features including `user_invite` (extensible later)
```

**Approach:** gateway BFF (same pattern as `/auth/*`, `/me`). Client never talks to Zitadel Management API.

## AuthZ

| Check | Rule |
|-------|------|
| Authentication | `Authorization: Bearer <access_token>` (JWT verified like `/me`) |
| Authorization | Caller must have feature **`user_invite`** (from role→features). Missing → `403` |
| MVP mapping | `admin` → `user_invite` (and existing admin features if any) |
| Later | Add `user_invite` to `dispatcher` / `reporter` in feature-service only |

Valid product roles for assignment (Zitadel project `masterdoc-toir`):

`admin` | `dispatcher` | `engineer` | `requester` | `reporter` | `technologist`

## Endpoints

Base: `https://api.masterdoc.pro`  
JSON: **camelCase**  
Errors: plain text or small JSON `{ "error": "…" }` with HTTP status (align with gateway: prefer short body; `401` / `403` / `400` / `409` / `502`)

### `POST /admin/users/invites`

Create user (invite flow) + assign roles + trigger Zitadel invitation email.

**Request:**

```json
{
  "email": "user@company.ru",
  "givenName": "Ivan",
  "familyName": "Petrov",
  "roles": ["technologist"]
}
```

| Field | Required | Notes |
|-------|----------|--------|
| `email` | yes | Unique in org |
| `givenName` | yes | |
| `familyName` | yes | |
| `roles` | yes | Non-empty; each key must be a known project role |

**Responses:**

- `201` — user resource (see shape below), `inviteSent: true` if IdP accepted send
- `400` — validation / unknown role
- `401` — no/invalid token
- `403` — no `user_invite`
- `409` — email already registered
- `502` — Zitadel unavailable / Management API failure

### `GET /admin/users`

List users in the product org (current Zitadel org used by platform).

**Query:** `limit` (default 50, max 100), `offset` (default 0)

**200:**

```json
{
  "items": [ /* User */ ],
  "total": 1
}
```

### `PUT /admin/users/{id}/roles`

Replace the user’s full set of project role grants with `roles`.

**Request:** `{ "roles": ["technologist", "reporter"] }`  
**200:** User resource  
**404:** unknown user id  
Same auth errors as above.

Empty `roles` is **400** (must keep at least one role) unless product later decides otherwise — **v1: reject empty**.

### `POST /admin/users/{id}/resend-invite` (v1 included)

Resend invitation / initialization email for users still in invited state.

**204** on success (no body).  
**409** if user is already `active` (password set).  
**404** if unknown id.

## User resource shape

```json
{
  "id": "3826…",
  "email": "user@company.ru",
  "givenName": "Ivan",
  "familyName": "Petrov",
  "roles": ["technologist"],
  "state": "invited",
  "inviteSent": true
}
```

| Field | Meaning |
|-------|---------|
| `id` | Zitadel user id (`sub`) |
| `state` | `invited` \| `active` \| `inactive` (mapped from Zitadel user state) |
| `inviteSent` | Only on invite/resend responses; omit on list if unknown |
| `roles` | Project role keys from user grants on `masterdoc-toir` |

## Zitadel mapping (implementation notes)

| REST action | IdP |
|-------------|-----|
| Invite | Create human user with invitation email (User Service v2 invite / equivalent); email verified flow per Zitadel invite docs |
| Roles | User grant on project `masterdoc-toir` with `roleKeys` |
| Resend | Zitadel resend invite / initialization code API |
| Password set / reset | Zitadel Login UI + email (SMTP must be configured ops-side) |

Gateway holds a **machine PAT** (or existing `terraform-masterdoc`-class credential) with rights to manage users/grants in the org — never exposed to the browser.

## feature-service change (small, required for gate)

| Role | Add feature |
|------|-------------|
| `admin` | `user_invite` |

No change to technologist features (`charts`, `equipment`).

## Testing (when implementing)

- Contract tests on gateway: 401/403 without feature; 400 unknown role; 409 duplicate email (mocked Zitadel)
- Mapping unit tests: Zitadel state → `invited`/`active`/`inactive`
- Optional live smoke behind secret: invite test mailbox (ops)

## Out of scope for first implementation plan follow-up

- Client UI for admin invites
- SMTP / email template branding
- Soft-delete / deactivate user endpoint (can add later as `POST /admin/users/{id}/deactivate`)
