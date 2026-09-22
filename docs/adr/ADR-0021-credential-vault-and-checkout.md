# ADR-0021: Privileged credential vault, verified rotation and time-bound checkout

Status: Accepted (Phase 6.1), 2026-09-22

## Context
Phase 6 requires that privileged passwords are known only to the platform, change after use, and are released only
temporarily, with approval, to named people. Vault (ADR-0005) is already the only secret store; the worker plane
already redeems single-use credential handles and verifies every change (G5).

## Decision
1. **Take-over = immediate rotation.** "Vault" on an account generates a 24-character password (four character
   classes, no quotes/colons/whitespace), writes it to Vault at `accounts/<accountId>/password` as a new version and
   sends `ROTATE_PASSWORD` with two handles: `connection` (service credential) and `new-secret` (the pending version).
   No provider needs the current password (Linux `chpasswd` via sudo, Windows `Set-LocalUser`, AD `unicodePwd`
   replace over TLS, PostgreSQL `ALTER ROLE ... PASSWORD` with a SCRAM-SHA-256 verifier computed by the worker, so
   the password never reaches the database or its logs).
2. **Promotion only after verification.** SUCCESS (read-back: Linux change date, Windows `PasswordLastSet`, AD
   `pwdLastSet`, PostgreSQL login test) promotes the pending version to current. FAILED keeps the old version.
   UNKNOWN keeps both; a reveal then offers both values, and the hourly job retries the rotation.
3. **Checkouts are exclusive and time-bound (1–72 h).** Only the holder can reveal, only with step-up, with
   `Cache-Control: no-store`; every reveal is audited and counted. Check-in, expiry (checked every minute) and
   revocation by a credential manager end the checkout and rotate the password if it was revealed. No rotation
   runs while a password is checked out.
4. **Requests reuse the access-request engine.** Request type `CREDENTIAL` (account + hours) is evaluated by the
   policy engine (P-300: justification + PAM administrator approval, max one day; P-910: no checkout for
   contractors/external identities); fulfilment calls `account::api.CredentialCheckouts.grant`; the checkout id is
   kept in `role_assignment_id` (the fulfilment reference) and the request follows the checkout state.
5. **Direct checkout** for holders of `credential:manage` (PLATFORM_ADMINISTRATOR, PAM_ADMINISTRATOR) with step-up,
   a mandatory reason and full audit — for single-administrator installations and emergencies.
6. **Scheduled rotation** of verified credentials after `iam.vault.rotation-interval` (default 30 days, per account
   `rotation_interval_days`).

## Consequences
- The platform's own service accounts are never rotated by this path (providers refuse PROTECTED_ACCOUNT).
- PostgreSQL 16 requires the service role to hold ADMIN on managed roles; the lab grants it.
- Session brokering (SSH/RDP proxy, recording) is still open; checkouts give the password to the person.
