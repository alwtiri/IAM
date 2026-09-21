package com.enterprise.iam.core.shared.api.security;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The authenticated principal of the current request, resolved from the platform database (never from token roles).
 *
 * @param identityId   platform identity
 * @param subject      Keycloak subject ({@code sub})
 * @param acr          authentication context class reference, may be null
 * @param amr          authentication methods, may be empty
 * @param authTime     time of the last interactive authentication, may be null for client credentials
 * @param channel      BROWSER_SESSION or BEARER_TOKEN
 * @param sourceIp     client address as seen by the Core (after trusted proxy headers)
 */
public record CurrentActor(UUID identityId, String subject, String acr, List<String> amr, Instant authTime,
                           Channel channel, String sourceIp) {

    public enum Channel { BROWSER_SESSION, BEARER_TOKEN, SYSTEM }

    public CurrentActor {
        Objects.requireNonNull(identityId, "identityId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(channel, "channel");
        amr = amr == null ? List.of() : List.copyOf(amr);
    }

    /** Actor used by scheduled jobs; always audited as SYSTEM. */
    public static CurrentActor system(UUID systemIdentityId) {
        return new CurrentActor(systemIdentityId, "system", null, List.of(), null, Channel.SYSTEM, null);
    }
}
