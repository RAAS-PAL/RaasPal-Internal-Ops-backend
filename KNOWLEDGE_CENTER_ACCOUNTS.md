# Knowledge Center accounts — Phase 2a API handoff

Implemented on `feat/knowledge-center-accounts`. Local only; no push, merge, deployment,
or connection of the sample-mode web app in this phase.

## Transport and envelopes

Requests are JSON. Successful requests return HTTP 200:

```json
{"success": true, "data": {}, "timestamp": "<server timestamp>"}
```

Expected failures return one translation code, never a sentence:

```json
{"success": false, "message": "expired", "timestamp": "<server timestamp>"}
```

The security filter's unauthenticated KC response omits `timestamp`. Clients should
read `success`, `data`, and `message`, not depend on optional envelope fields.
No endpoint sets a browser cookie. The next frontend phase must keep the returned
`accessToken` server-side in its httpOnly session cookie and send it to protected
endpoints as `Authorization: Bearer <accessToken>`.

## Endpoints

### POST /api/v1/kc/auth/send-code (public)

```json
{"purpose":"signup","email":"employee@raaspal.com"}
```

`purpose`: `signup` / `reset`, also accepts `SIGNUP` / `RESET`.
Emails are trimmed and case-folded with `Locale.ROOT`. The code forms also accept
a local part such as `employee`, appending `@raaspal.com`, as the sample actions do.
Only the exact `raaspal.com` domain is allowed; subdomains and lookalike suffixes fail.

Success data: `{"email":"employee@raaspal.com"}` (normalized).

- HTTP 400 `invalid`: missing/malformed email or purpose.
- HTTP 400 `domain`: an address outside the company domain.
- HTTP 400 `exists`: signup for any existing account, including an inactive account.
- HTTP 429 `unavailable`, with integer `Retry-After` seconds: cooldown/hourly cap.
- HTTP 503 `unavailable`: transport or service failure; do not display server details.

Reset for an unknown or inactive account returns the same successful data and uses
the same per-email cooldown/budget as an active account. It does not send mail or
create/reactivate an account. Signup never alters an existing user.

### POST /api/v1/kc/auth/verify-code (public)

```json
{"purpose":"signup","email":"employee@raaspal.com","code":"<six decimal digits>"}
```

Success data: `{"ticket":"<43-character opaque token>"}`.

- HTTP 400 `wrong`: incorrect/malformed code or invalid email/purpose.
- HTTP 400 `expired`: no active code, wrong purpose, elapsed expiry, already verified,
  or five attempts already used. The fifth wrong guess returns `wrong`; the next
  verification returns `expired`. A correct fifth attempt succeeds.
- HTTP 503 `unavailable`: service failure.

### POST /api/v1/kc/auth/signup (public)

```json
{"ticket":"<ticket>","name":"Test Employee","password":"<password>","confirm":"<same password>"}
```

`confirm` is optional because the UI already checks it. The ticket owns the email;
no email supplied by the client can change which account is created. Name whitespace
is collapsed; names must be nonblank and fit the existing 255-character column.

Success data is the existing `AuthResponse`:

```json
{
  "accessToken":"<JWT>",
  "tokenType":"Bearer",
  "user":{
    "id":"<UUID>",
    "email":"employee@raaspal.com",
    "fullName":"Test Employee",
    "role":"STAFF",
    "active":true,
    "createdAt":"<timestamp>",
    "updatedAt":"<timestamp>"
  }
}
```

The new user has `kc_role=VIEWER`. Read KC identity through `/api/v1/kc/auth/me`;
`UserResponse` remains the common Ops/RIMS shape and does not include `kc_role`.

- HTTP 400 `name`: blank/too-long name.
- HTTP 400 `short`: password has fewer than eight Java/JavaScript string characters.
- HTTP 400 `mismatch`: optional confirmation differs.
- HTTP 400 `expired`: ticket missing, malformed, expired, used, wrong-purpose,
  replaced by a resend, or the account was created after code verification.
- HTTP 400 `unavailable`: password exceeds BCrypt's 72 UTF-8 byte maximum.
- HTTP 503 `unavailable`: service failure.

### POST /api/v1/kc/auth/reset (public)

```json
{"ticket":"<reset ticket>","password":"<new password>","confirm":"<same password>"}
```

Success uses the same `AuthResponse`. Changes the existing active user's password,
preserving their Ops role and any existing KC role. A null KC role becomes `VIEWER`.
Errors match signup's password/ticket errors (`short`, `mismatch`, `expired`,
`unavailable`), excluding `name`. Disabled/deleted users cannot finish reset.

### POST /api/v1/auth/login (existing, public)

```json
{"email":"employee@raaspal.com","password":"<password>","audience":"kc"}
```

Uses the same account, password hash and JWT as Ops and RIMS; no email code.
The KC frontend must include `audience:"kc"` to enforce domain validation at login.
Legacy callers omit it and retain login for their existing domains. `audience` is
a login policy selector, not an authority: `/kc/auth/me` independently refuses
non-company principals even when their token came from a legacy login.
Use a complete, normalized email here (the existing DTO validates email syntax).

Success: existing `AuthResponse`; a null KC role becomes `VIEWER` on a KC login.
The success envelope no longer includes the old English "Login successful" message.

- HTTP 400 `invalid`: invalid/missing email or unsupported audience.
- HTTP 400 `domain`: non-company email with `audience:"kc"`.
- HTTP 400 `credentials`: missing/blank password.
- HTTP 401 `credentials`: wrong email/password or inactive account.

The frontend should map unexpected/non-JSON/network failures to `unavailable`,
as it does for every other request. Ordinary login failure now uses HTTP 401 and
the `credentials` code for all clients (formerly HTTP 400 with English prose).

### GET /api/v1/kc/auth/me (Bearer JWT required)

Success data:

```json
{"name":"Test Employee","email":"employee@raaspal.com","kc_role":"VIEWER"}
```

Returns the stored email spelling, which may retain capitals on older accounts.
Initializes only a null KC role to `VIEWER`; preserves `EDITOR` / `ADMIN` and the
existing Ops role. HTTP 401 `credentials` for missing/invalid/inactive authentication;
HTTP 403 `domain` for a non-company account. Uses a row lock to protect initialization.

## Persistence and security

- `V64__add_knowledge_center_accounts.sql`: nullable constrained `users.kc_role`,
  a unique index on `lower(users.email)`, and `email_codes` with email/purpose lookup
  and unique ticket-hash indexes. Existing case-variant duplicate users must be
  resolved before migration; the migration deliberately fails instead of merging users.
- One durable slot per normalized email; resend replaces code and ticket together.
  Code hashes use the shared BCrypt encoder; six digits come from `SecureRandom`.
  Tickets are 32 random bytes, URL-safe base64, and only SHA-256 hashes are stored.
- Codes expire after 10 minutes and allow at most five attempts. Tickets expire
  10 minutes after verification and can be consumed once. Database locks serialize
  resends, attempts, ticket consumption and password changes across instances.
- Five sends per fixed one-hour window beginning with the first send; at least
  60 seconds between sends. Both purposes share the budget. State survives restarts.
- SMTP uses the existing `JavaMailSender`, `MAIL_*` configuration and `app.mail.from`.
  Plain UTF-8 text includes Thai and English, purpose, code, lifetime and ignore notice.
  No reporting CC/BCC; request DTOs redact credentials in `toString`; application
  code never logs codes, passwords, ticket values or message bodies.
- SMTP failure rolls back code replacement so an earlier valid code/ticket survives.
  Shared SMTP connection/read/write waits are now bounded to 5/10/10 seconds.
- `STAFF` is rejected before every legacy `/api/v1/**` handler, including public
  report/PIN routes and shared `/auth/me`, except shared login. KC's four public
  POST endpoints and authenticated GET `/me` are explicitly allowed. Unimplemented
  KC paths deny access. Partner OAuth uses its separate, unchanged signing key/chain.
- **Existing JWT behavior:** reset changes the password for future sign-ins but
  does not revoke previously issued JWTs. They expire on the platform's existing
  JWT lifetime. This phase does not introduce token revocation.

## Frontend findings (read-only inspection; no frontend edits)

Ops checkout is still named `robot-recommendation-web-raaspal` on this PC (repo is
`RaasPal-Ops-frontend`). `proxy.ts` checks cookie presence and `store/auth.ts` accepts
the returned user without a general role gate. A `STAFF` login can therefore enter
the shell; protected API calls now return 403. A later UI phase should give a clear,
translated no-access route rather than showing empty/error panels.

RIMS `lib/backend-types.ts:rolesForBackendRole` maps unknown roles to `[]`.
`lib/auth.ts:signIn` rejects an empty role list before creating a session, and
`getCurrentUser` treats `/auth/me` 401/403 as signed out. `lib/rbac.ts` grants no
capabilities for unknown roles. `STAFF` therefore cannot enter RIMS.

`users.role` is a Java enum, stored in `VARCHAR(20)` with no SQL CHECK in the existing
migrations; adding `STAFF` needs no change to that column. No live database was queried.

## Validation and release handoff

Tests run with `JAVA_HOME=C:/Program Files/Java/jdk-21.0.10`, H2 and Flyway disabled.
`mvnw.cmd -B -ntp verify` is the Windows counterpart of CI's `./mvnw -B -ntp verify`.
Final local run, 2026-10-06 (37 new tests included):

```text
KC security: STAFF denied on 220 mapped legacy API operations
[INFO] Tests run: 588, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[INFO] Total time:  53.873 s
```

The log is `target/kc-full-verify.log` (local build output, not committed).
The new tests exercise API errors, case-insensitive identity, exact expiry boundaries,
attempt persistence, throttling, single-use tickets, concurrency, mail rollback and
bilingual message delivery. A test enumerates every mapped legacy `/api/v1` operation
and requires 403 for `STAFF`, including future operations added to that mapping set.
H2 tests do not execute the PostgreSQL Flyway script. No Supabase startup, real email
delivery, production schema change or deployment was performed.

**Migration ordering is still a release blocker:** V64 was unused on all fetched
remote refs; `main` ended at V62. V63 belongs to the other session's AOT sheet work.
Do not ship V64 before V63. Coordinate numbering/order before any merge/deploy; never
change an already applied migration. Phase 2b's review-policy decision, content tables,
editing endpoints, frontend connection and deployment remain outside Phase 2a.

Local implementation commits, in dependency order:

- `bce2bac` — migration.
- `e8a21a9` — roles, entity and repositories.
- `7aa0d0b` — verification, tickets and account service.
- `fdd8af8` — controllers, shared login and authorization.
- `4f5a1c5` — bilingual SMTP mail.
- `5f674c7` — consistent errors and bounded SMTP waits.
- `3659f18` — unit/integration/security tests.

Changed source areas: `common/enums/Role`, `user/entity/User`, `user/repository/UserRepository`,
`auth/{controller,dto,security,service}`, the new `knowledge` package,
`application.properties`, V64, and `src/test/java/.../knowledge`. This document is the
frontend connection contract. No frontend source or sample-mode branch was edited.
