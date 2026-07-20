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
FEATURE_SERVICE_BASE_URL=http://127.0.0.1:8082
BACKEND_BASE_URL=http://127.0.0.1:8081
CORS_ORIGINS=https://copilot.formaverse.ru,https://copilot.masterdoc.pro,http://localhost:8080
```

Green slot uses `PORT=8084` via compose.

**Zitadel:** no new client secret for the gateway (JWT validation only).

## GitHub Secrets (`masterdoc-app/api-gateway-service`)

| Secret | Purpose |
|--------|---------|
| `DEPLOY_SSH_PRIVATE_KEY` | SSH to VPS |
| `DEPLOY_USER` | SSH user |
| `DEPLOY_HOST` | VPS host |

Env file on VPS is not committed.

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
