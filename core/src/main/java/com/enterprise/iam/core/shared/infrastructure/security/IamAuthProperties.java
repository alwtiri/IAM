package com.enterprise.iam.core.shared.infrastructure.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Keycloak integration settings (ADR-0016). Browser-facing URLs (issuer, authorization, end-session) use the public
 * host; back-channel URLs (token, JWKS, userinfo) use the internal service name. No discovery call is made at
 * startup, so the Core starts even when Keycloak is down (G2, RES5).
 */
@ConfigurationProperties(prefix = "iam.auth")
public record IamAuthProperties(
        String issuer,
        String authorizationUri,
        String tokenUri,
        String jwkSetUri,
        String userInfoUri,
        String endSessionUri,
        String clientId,
        String clientSecret,
        String audience,
        String postLogoutRedirectUri,
        boolean secureCookies) {
}
