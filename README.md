# api-gateway-service

Ktor gateway — **единственный API URL** для клиентов: `https://api.masterdoc.pro`.

| Path | Auth | Upstream |
|------|------|----------|
| `GET /health` | — | gateway |
| `GET /auth/url` | — | gateway → `{issuer}/oauth/v2/authorize` |
| `POST /auth/token` | — | BFF → Zitadel `/oauth/v2/token` |
| `GET /me` | Bearer Zitadel JWT | feature-service `:8082` |
| `/v1/*` | passthrough | backend `:8081` |
| `/sites`, `/assets` | Bearer JWT | catalog-service `:8091` (+ audit → black-box) |
| `GET /admin/audit` | Bearer + `user_invite` | black-box-service `:8095` |

Клиент знает только gateway. Логин UI — Zitadel; обмен code/refresh — `POST /auth/token`.

Контракт: [`openapi.yaml`](openapi.yaml).  
Auth простыми словами: [`docs/AUTH.md`](docs/AUTH.md).  
Секреты и cutover: [`docs/SECRETS_AND_DOMAINS.md`](docs/SECRETS_AND_DOMAINS.md).

## Local

```bash
export FEATURE_SERVICE_BASE_URL=http://127.0.0.1:8082
export BACKEND_BASE_URL=http://127.0.0.1:8081
export ZITADEL_ISSUER=https://auth.fixaverse.ru
export ZITADEL_JWK_SET_URI=https://auth.fixaverse.ru/oauth/v2/keys
./gradlew run
curl -s http://127.0.0.1:8083/health
```

Tests (TDD):

```bash
./gradlew test
```

## Deploy (blue-green only)

Do **not** use bare `docker compose up` for production.

1. First time: copy `deploy/.env.example` → `/etc/masterdoc-api-gateway/.env`
2. Install nginx site from `deploy/api.masterdoc.pro.nginx.conf` + `api-upstream.conf`
3. `echo blue | sudo tee /etc/masterdoc-api-gateway/active-slot`
4. Build image, run `deploy/blue-green.sh`

CI on `main`: test → build image → SSH → `blue-green.sh`.

## Rate limiting

Not in MVP — note for later at nginx or gateway plugin.
