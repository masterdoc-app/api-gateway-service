# User-scopes admin fix

- Changed `/user-scopes` reads to allow `admin` and `board`.
- Changed `/user-scopes` writes to allow `admin` only.
- Added coverage for admin GET/PUT, board GET/PUT, and tickets GET/PUT.
- Targeted test: `./gradlew test --tests 'pro.masterdoc.gateway.UserScopeProxyRoutesTest'`
- Result: BUILD SUCCESSFUL.
- Branch: `feat/customer-tickets-user-scopes-admin`
