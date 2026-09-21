package com.enterprise.iam.core.identity.infrastructure.security;

import com.enterprise.iam.core.identity.application.ActorResolver;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.kernel.IamException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Resolves the {@link CurrentActor} from the Spring Security context once per request (ADR-0016). Only {@code sub},
 * {@code acr}, {@code amr}, {@code auth_time} and profile hints for the one-time bootstrap are read from tokens.
 */
public class SpringSecurityActorProvider implements CurrentActorProvider {

    private static final String ATTRIBUTE = SpringSecurityActorProvider.class.getName() + ".actor";

    private final ActorResolver resolver;

    public SpringSecurityActorProvider(ActorResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public Optional<CurrentActor> current() {
        HttpServletRequest request = request();
        if (request != null && request.getAttribute(ATTRIBUTE) instanceof CurrentActor cached) {
            return Optional.of(cached);
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return Optional.empty();
        }
        String ip = request == null ? null : request.getRemoteAddr();
        ActorResolver.AuthenticatedPrincipal principal;
        if (auth.getPrincipal() instanceof OidcUser oidc) {
            principal = new ActorResolver.AuthenticatedPrincipal(oidc.getSubject(), oidc.getIdToken().getClaimAsString("acr"),
                    list(oidc.getIdToken().getClaimAsStringList("amr")), oidc.getIdToken().getAuthenticatedAt(),
                    CurrentActor.Channel.BROWSER_SESSION, ip, oidc.getPreferredUsername(), oidc.getGivenName(), oidc.getFamilyName(),
                    oidc.getEmail());
        } else if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            Instant authTime = jwt.hasClaim("auth_time") ? jwt.getClaimAsInstant("auth_time") : null;
            principal = new ActorResolver.AuthenticatedPrincipal(jwt.getSubject(), jwt.getClaimAsString("acr"),
                    list(jwt.getClaimAsStringList("amr")), authTime, CurrentActor.Channel.BEARER_TOKEN, ip,
                    jwt.getClaimAsString("preferred_username"), null, null, null);
        } else {
            return Optional.empty();
        }
        if (principal.subject() == null || principal.subject().isBlank()) {
            return Optional.empty();
        }
        CurrentActor actor = resolver.resolve(principal);
        if (request != null) {
            request.setAttribute(ATTRIBUTE, actor);
        }
        return Optional.of(actor);
    }

    @Override
    public CurrentActor require() {
        return current().orElseThrow(IamException::authenticationRequired);
    }

    private static List<String> list(List<String> l) {
        return l == null ? List.of() : l;
    }

    private static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a ? a.getRequest() : null;
    }
}
