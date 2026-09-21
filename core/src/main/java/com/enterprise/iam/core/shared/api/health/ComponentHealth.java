package com.enterprise.iam.core.shared.api.health;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Health of one component with the functionality it affects (spec §62, OpenAPI ComponentHealth). */
public record ComponentHealth(String component, Category category, Classification classification, Status status,
                              String reason, List<String> affectedFunctionality, Instant checkedAt) {

    public enum Status { HEALTHY, DEGRADED, UNAVAILABLE, UNKNOWN }

    public enum Category { APPLICATION, DATABASE, CACHE, MESSAGE_BROKER, SECRETS, AUTHENTICATION, PROVIDER, AGENT, GATEWAY, WORKER, INTEGRATION }

    public enum Classification { CORE, CORE_DEPENDENCY, EXTENSION, OPTIONAL }

    public ComponentHealth {
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(checkedAt, "checkedAt");
        affectedFunctionality = status == Status.HEALTHY || affectedFunctionality == null ? List.of() : List.copyOf(affectedFunctionality);
    }
}
