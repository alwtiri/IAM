package com.enterprise.iam.core.shared.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Adds a forced re-authentication to the authorization request when the SPA starts a step-up
 * ({@code /oauth2/authorization/keycloak?stepup=1}) after receiving {@code STEP_UP_REQUIRED} (ADR-0016):
 * {@code prompt=login}, {@code max_age=0} and {@code acr_values=mfa}.
 */
final class StepUpAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private final OAuth2AuthorizationRequestResolver delegate;

    StepUpAuthorizationRequestResolver(OAuth2AuthorizationRequestResolver delegate) {
        this.delegate = delegate;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return customize(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return customize(request, delegate.resolve(request, clientRegistrationId));
    }

    private static OAuth2AuthorizationRequest customize(HttpServletRequest request, OAuth2AuthorizationRequest original) {
        if (original == null || request.getParameter("stepup") == null) {
            return original;
        }
        return OAuth2AuthorizationRequest.from(original)
                .additionalParameters(p -> {
                    p.put("prompt", "login");
                    p.put("max_age", "0");
                    p.put("acr_values", "mfa");
                })
                .build();
    }
}
