# Provider plugins

One Gradle project per provider type (`providers/<type>`), plain Java libraries that depend only on `provider-spi`
(MODULE-BOUNDARIES §1, ADR-0003, ADR-0018). The worker loads them with `ServiceLoader` via
`META-INF/services/com.enterprise.iam.provider.spi.ProviderFactory`. Every provider passes the contract kit
(`provider-spi-testkit`) and protocol-level tests.

## Onboarding a Linux server (linux-ssh)

1. Create a service account on the server (key authentication only) and give it least-privilege sudo for exactly the
   commands the provider runs (see `deploy/compose/lab/linux/sudoers-svc-iam`):
   `svc-iam ALL=(root) NOPASSWD: /usr/bin/true, /usr/bin/passwd -S *, /usr/bin/chage -l *, /usr/sbin/usermod, /usr/sbin/faillock`
2. Read the host key fingerprint from the server itself (not over the network):
   `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub -E sha256` → `SHA256:...`.
   If the connection test reports "server presented <type> SHA256:...", the server negotiated another key type; verify
   that fingerprint on the server and use it.
3. In the UI: Assets → Servers → Add server → Add connection (paste the private key; it is stored in Vault only) →
   Test connection → Discover accounts.

End-to-end check against a lab server: `deploy/compose/scripts/smoke-phase3.sh`.
