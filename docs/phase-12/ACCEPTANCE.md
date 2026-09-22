# Final acceptance (Phase 12)

Run `deploy/compose/scripts/acceptance-check.sh` first (no login needed), then walk through this list in the UI.
Tick each line; any failure blocks acceptance.

| # | Area | Test | Expected |
|---|---|---|---|
| 1 | Sign-in | Log in with password + TOTP | Dashboard opens; name and role in the header |
| 2 | Users | Add a user, activate, create login | Invitation e-mail in Mailpit; user can set password and MFA |
| 3 | Roles | Grant and revoke a role | Audit shows role-assignment.granted/revoked |
| 4 | Servers | Add lab-linux, add SSH connection, test | SUCCESS with host key pinned |
| 5 | Discovery | Discover accounts | Accounts listed; privileged ones flagged with the reason |
| 6 | Lifecycle | Disable then enable an account | SUCCESS with read-back verification |
| 7 | Edit/delete | Edit the server, disable/enable the connection, delete a test server | Changes visible and audited; deleted server gone from lists |
| 8 | Vault | Vault alice; wait | VERIFIED |
| 9 | Checkout | Check out, show password, log in on the server with it, check in | Login works; after check-in the old password fails |
| 10 | Request | User requests a password; PAM administrator approves | Checkout ACTIVE for the requester |
| 11 | Break-glass | Mark emergency; break glass | Security e-mail sent; review pending; reviewer (other person) reviews |
| 12 | Database | lab-postgres: connect, discover, vault app_writer | VERIFIED by login test |
| 13 | Policies | Access request for a privileged role | Two approval steps; SoD conflicts shown |
| 14 | Reports | Download each CSV | Opens in Excel; no formula injection |
| 15 | Audit | Audit Logs, Access Logs, Configuration Changes | Events listed; audit verification (MFA) OK |
| 16 | Settings | Administration → System Settings / Integrations | Effective values; no secrets shown |
| 17 | Backup | `backup.sh`, then `restore.sh` on a spare host | Platform works after restore |
| 18 | Dark mode / Arabic | Toggle both | Layout correct in RTL and dark |
