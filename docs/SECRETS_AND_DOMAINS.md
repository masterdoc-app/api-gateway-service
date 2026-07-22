# Secrets & domains — api-gateway-service

## Single API URL (clients)

| What | Value |
|------|--------|
| **Only API base** | `https://api.masterdoc.pro` |
| Login (OIDC UI) | `https://auth.fixaverse.ru` (URL also via `GET /auth/url`) |

Clients call:

- `GET https://api.masterdoc.pro/me` (+ Bearer)
- `https://api.masterdoc.pro/v1/...` (chat etc.)

Do **not** expose `feature-service:8082` or `backend:8081` publicly.

## DNS / TLS

- DNS for `api.masterdoc.pro` already points at the API VPS.
- After cutover, nginx terminates TLS and proxies to gateway blue/green (`127.0.0.1:8083` or `:8084`).
- Certbot (if renewing): `certbot --nginx -d api.masterdoc.pro`

## Server env (`/etc/masterdoc-api-gateway/.env`)

```bash
PORT=8083
ZITADEL_ISSUER=https://auth.fixaverse.ru
ZITADEL_JWK_SET_URI=https://auth.fixaverse.ru/oauth/v2/keys
ZITADEL_PROJECT_ID=<masterdoc-toir-project-id>
ZITADEL_MGMT_TOKEN=<pat-with-user-and-grant-management>
FEATURE_SERVICE_BASE_URL=http://127.0.0.1:8082
BACKEND_BASE_URL=http://127.0.0.1:8081
CORS_ORIGINS=https://app.fixaverse.ru,https://copilot.fixaverse.ru,https://copilot.formaverse.ru,https://copilot.masterdoc.pro,http://localhost:8080
```

Green slot uses `PORT=8084` via compose.

**Zitadel JWT validation:** `ZITADEL_ISSUER` + `ZITADEL_JWK_SET_URI` only (no client secret for end-user OAuth).

**Zitadel Management API** (`/admin/users*`):

| Variable | Purpose |
|----------|---------|
| `ZITADEL_PROJECT_ID` | Project for role grants (e.g. `masterdoc-toir`) |
| `ZITADEL_MGMT_TOKEN` | Personal access token (PAT) with rights to manage users and grants in client orgs |

Admin org scoping comes from JWT (ambient `TenantContext`), not from gateway env: prefer `urn:zitadel:iam:org:id`, else `urn:zitadel:iam:user:resourceowner:id` (OIDC scope `urn:zitadel:iam:user:resourceowner` on the client). Platform `ZITADEL_ORG_ID` remains for **`masterdoc-zitadel` Terraform only**; the PAT must be allowed to manage users and grants **across client orgs**.

- PAT is **server-only** — never ship to browser, mobile, or admin SPA bundles.
- Prefer a **dedicated machine user** service account with minimal IAM; do not reuse a human admin password.
- If unset, admin routes return `502` with a misconfiguration message on first Zitadel call.
- See `openapi.yaml` for `/admin/users*` contract; callers still need Bearer JWT + feature `user_invite`.

## GitHub Secrets (`masterdoc-app/api-gateway-service`)

| Secret | Purpose |
|--------|---------|
| `DEPLOY_SSH_PRIVATE_KEY` | SSH to VPS |
| `DEPLOY_USER` | SSH user |
| `DEPLOY_HOST` | VPS host |
| `ZITADEL_PROJECT_ID` | Project for role grants, e.g. `382623622436487171` (masterdoc-toir) |
| `ZITADEL_MGMT_TOKEN` | PAT with user/grant management. Prefer `terraform-masterdoc` (`ZITADEL_TOKEN` in masterdoc-zitadel). If using bootstrap `login-client`, grant it **ORG_OWNER** (or at least `ORG_USER_MANAGER`) on each client org via workflow **Ensure Mgmt PAT Org Member**. |

Env file on VPS is not committed. Deploy job upserts the two `ZITADEL_*` admin vars when secrets are set.

## Cutover checklist (nginx)

1. Backend listens on `127.0.0.1:8081` only (not public).
2. feature-service on `127.0.0.1:8082`.
3. First gateway blue on `:8083`; `api-upstream.conf` → `127.0.0.1:8083`.
4. Point `api.masterdoc.pro` nginx `proxy_pass` at upstream include (not straight to 8081).
5. `nginx -t && systemctl reload nginx`.
6. Smoke: `curl -sS https://api.masterdoc.pro/health` and `/v1/assistants`.
7. Further deploys: **only** `deploy/blue-green.sh` (never bare `compose up` for prod).

## Blue-green

Slots: blue `:8083`, green `:8084`. Script waits for `/health`, rewrites `api-upstream.conf`, `nginx -s reload`, stops old container.
