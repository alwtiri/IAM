# Provider plugins

One Gradle project per provider type (`providers/<type>`), plain Java libraries that depend only on `provider-spi`
(MODULE-BOUNDARIES §1, ADR-0003, ADR-0018). The worker loads them with `ServiceLoader` via
`META-INF/services/com.enterprise.iam.provider.spi.ProviderFactory`. Every provider passes the contract kit
(`provider-spi-testkit`) and protocol-level tests.
