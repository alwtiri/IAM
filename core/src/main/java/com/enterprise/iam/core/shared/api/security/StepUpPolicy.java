package com.enterprise.iam.core.shared.api.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Step-up rule (SEC5, ADR-0016): the actor authenticated interactively and recently with multi-factor authentication,
 * evidenced either by an accepted {@code acr} (level-of-assurance flows) or by an accepted {@code amr} method such as
 * {@code otp} or {@code hwk} (WebAuthn). Bearer tokens obtained with client credentials never satisfy step-up.
 */
public record StepUpPolicy(Set<String> acceptedAcrValues, Set<String> acceptedAmrValues, Duration maxAuthenticationAge) {

    public static final StepUpPolicy DEFAULT = new StepUpPolicy(Set.of("mfa", "2", "gold"), Set.of("otp", "mfa", "hwk", "swk"),
            Duration.ofMinutes(5));

    public StepUpPolicy {
        acceptedAcrValues = Set.copyOf(acceptedAcrValues);
        acceptedAmrValues = Set.copyOf(acceptedAmrValues);
        Objects.requireNonNull(maxAuthenticationAge, "maxAuthenticationAge");
    }

    public boolean isSatisfied(CurrentActor actor, Instant now) {
        if (actor == null || actor.channel() == CurrentActor.Channel.SYSTEM) {
            return false;
        }
        boolean mfa = (actor.acr() != null && acceptedAcrValues.contains(actor.acr()))
                || actor.amr().stream().anyMatch(acceptedAmrValues::contains);
        if (!mfa) {
            return false;
        }
        Instant authTime = actor.authTime();
        return authTime != null && !authTime.isAfter(now.plusSeconds(30))
                && Duration.between(authTime, now).compareTo(maxAuthenticationAge) <= 0;
    }
}
