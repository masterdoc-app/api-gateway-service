# Авторизация простыми словами

Клиент знает **только** gateway (`https://api.masterdoc.pro`) и его OpenAPI.
Пароли хранит Zitadel; gateway токены выдаёт как «окошко» к Zitadel.

## Что вызывает клиент (REST)

| Метод | Зачем |
|-------|--------|
| `GET /auth/url` | Получить ссылку на страницу логина |
| `POST /auth/token` | Обменять `code` или `refresh_token` на токены |
| `GET /me` | Кто я и какие фичи (уже с Bearer) |

Страница ввода пароля — на Zitadel (браузер). Обмен кода на токены — **через gateway**.

## Пошагово

1. Клиент: `GET /auth/url` → получает `authUrl`.
2. Открывает `authUrl` в браузере / WebView / Custom Tab.
3. Пользователь вводит email и пароль на Zitadel.
4. Zitadel редиректит на `redirect_uri` клиента с `?code=...&state=...`
   (куда — сказал сам клиент в authorize; web URL или deep link).
5. Клиент читает `code` из callback.
6. Клиент: `POST /auth/token` на **gateway** (form-urlencoded):
   - `grant_type=authorization_code`
   - `code`, `redirect_uri`, `client_id`, `code_verifier` (PKCE)
7. Gateway проксирует запрос в Zitadel `/oauth/v2/token` и возвращает ответ как есть:
   `access_token`, `refresh_token`, …
8. Клиент сохраняет токены и ходит в API: `Authorization: Bearer <access_token>`.
9. Когда access истёк — снова `POST /auth/token` с `grant_type=refresh_token`.

## Важно

- Один swagger / один `base_url` для REST — gateway.
- `POST /auth/token` принимает оба grant: code и refresh.
- UI логина остаётся на домене IdP; это нормально для OIDC.
