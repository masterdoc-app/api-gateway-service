# Авторизация простыми словами

Клиент знает **только** gateway (`https://api.masterdoc.pro`) и его OpenAPI.
Пароли хранит Zitadel; gateway токены выдаёт как «окошко» к Zitadel.

## Что вызывает клиент (REST)

| Метод | Зачем |
|-------|--------|
| `GET /auth/url` | Получить ссылку на страницу логина |
| `POST /auth/login` | Android: войти по email/паролю внутри приложения, без браузера |
| `POST /auth/token` | Обменять `code` или `refresh_token` на токены |
| `GET /me` | Кто я и какие фичи (уже с Bearer) |
| `/admin/users*` | Приглашение и управление пользователями (Bearer + feature `admin`; см. `openapi.yaml`) |

Web использует страницу ввода пароля Zitadel в браузере. Android отправляет email/пароль
на gateway `POST /auth/login`; gateway не хранит пароль и передаёт его в Zitadel Session API.
В обоих случаях код обменивается на токены через gateway.

## Web: пошагово

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

## Android: вход без браузера

1. Клиент отправляет JSON `{email, password, client_id}` в `POST /auth/login`.
2. Gateway создаёт PKCE и OIDC auth request с redirect URI `masterdoc://auth/callback`.
3. Gateway проверяет логин/пароль через Zitadel Session API и финализирует auth request.
4. Полученный code gateway обменивает через Zitadel token endpoint.
5. Клиент получает обычные `access_token`, `refresh_token`, `id_token` и дальше использует
   тот же `GET /me` и `POST /auth/token` для refresh.

Пароль не сохраняется и не логируется gateway. `401` всегда означает общее
«неверный email или пароль», без раскрытия существования пользователя.

## Важно

- Один swagger / один `base_url` для REST — gateway.
- `POST /auth/token` принимает оба grant: code и refresh.
- Web UI логина остаётся на IdP (`auth.fixaverse.ru`); Android использует BFF login без браузера.
- Gateway на VPS резолвит IdP через `extra_hosts` → host nginx (DNS с контейнера часто недоступен).
