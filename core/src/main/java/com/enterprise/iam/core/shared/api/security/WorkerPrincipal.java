package com.enterprise.iam.core.shared.api.security;

import java.util.Objects;
import java.util.Set;

/**
 * A worker authenticated by its mTLS client certificate on the internal listener.
 *
 * @param subject       certificate common name, e.g. {@code iam-worker-os}
 * @param providerTypes provider types whose operations this worker may serve (and whose credential handles it may redeem)
 */
public record WorkerPrincipal(String subject, Set<String> providerTypes) {

    /** Request attribute under which the internal filter stores the authenticated worker. */
    public static final String REQUEST_ATTRIBUTE = "iam.worker";

    public WorkerPrincipal {
        Objects.requireNonNull(subject, "subject");
        providerTypes = Set.copyOf(providerTypes);
    }

    public boolean serves(String providerType) {
        return providerTypes.contains(providerType);
    }
}
