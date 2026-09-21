package com.enterprise.iam.core.shared.infrastructure.security;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Authentication and HTTP security (ADR-0016, SECURITY-ARCHITECTURE §3, §9).
 *
 * <ul>
 *   <li>Browser: OAuth2 Login (authorization code + PKCE) — tokens stay in the server session.</li>
 *   <li>Automation: OAuth2 Resource Server (JWT, issuer + audience validated, JWKS fetched lazily and cached).</li>
 *   <li>Deny by default; {@code /api/**} requires authentication; authorization is enforced per handler and object.</li>
 *   <li>Cookie sessions are CSRF-protected ({@code XSRF-TOKEN} → {@code X-XSRF-TOKEN}); bearer calls are exempt.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IamAuthProperties.class)
class SecurityConfiguration {

    static final String REGISTRATION_ID = "keycloak";

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(IamAuthProperties p) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(p.clientId())
                .clientSecret(p.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .authorizationUri(p.authorizationUri())
                .tokenUri(p.tokenUri())
                .jwkSetUri(p.jwkSetUri())
                .userInfoUri(p.userInfoUri())
                .userNameAttributeName("sub")
                .issuerUri(p.issuer())
                .providerConfigurationMetadata(Map.of("end_session_endpoint", p.endSessionUri()))
                .clientName("Keycloak")
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    JwtDecoder jwtDecoder(IamAuthProperties p) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(p.jwkSetUri()).build();
        JwtClaimValidator<List<String>> audience =
                new JwtClaimValidator<>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(p.audience()));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(p.issuer()), audience));
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, ClientRegistrationRepository registrations,
                                            IamAuthProperties props, Clock clock) throws Exception {
        RequestMatcher api = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
        RequestMatcher bearer = (HttpServletRequest r) -> {
            String h = r.getHeader("Authorization");
            return h != null && h.regionMatches(true, 0, "Bearer ", 0, 7);
        };

        DefaultOAuth2AuthorizationRequestResolver pkceResolver =
                new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        pkceResolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
        OAuth2AuthorizationRequestResolver pkce = new StepUpAuthorizationRequestResolver(pkceResolver);

        OidcClientInitiatedLogoutSuccessHandler logout = new OidcClientInitiatedLogoutSuccessHandler(registrations);
        logout.setPostLogoutRedirectUri(props.postLogoutRedirectUri());

        CookieCsrfTokenRepository csrfRepo = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepo.setCookieCustomizer(c -> c.sameSite("Strict").secure(props.secureCookies()).path("/"));
        CsrfTokenRequestAttributeHandler csrfHandler = new CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null); // plain token, readable by the SPA from the cookie

        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/liveness", "/actuator/health/readiness", "/actuator/prometheus").permitAll()
                .requestMatchers("/oauth2/**", "/login/**").permitAll()
                .requestMatchers("/error").permitAll() // error rendering only; content is the §73 model, never stack traces
                .requestMatchers(api).authenticated()
                .requestMatchers("/logout").authenticated()
                .anyRequest().denyAll())
            .oauth2Login(o -> o
                .authorizationEndpoint(ae -> ae.authorizationRequestResolver(pkce))
                .defaultSuccessUrl("/", true))
            .oauth2ResourceServer(o -> o.jwt(jwt -> { }))
            .logout(l -> l.logoutUrl("/logout").logoutSuccessHandler(logout))
            .csrf(c -> c.csrfTokenRepository(csrfRepo).csrfTokenRequestHandler(csrfHandler).ignoringRequestMatchers(bearer))
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            .exceptionHandling(e -> e
                .defaultAuthenticationEntryPointFor(JsonSecurityHandlers.unauthorized(clock), api)
                .defaultAccessDeniedHandlerFor(JsonSecurityHandlers.forbidden(clock), api))
            .sessionManagement(s -> s.sessionFixation(f -> f.changeSessionId()))
            .headers(h -> h
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .frameOptions(f -> f.deny())
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000)));
        return http.build();
    }
}
