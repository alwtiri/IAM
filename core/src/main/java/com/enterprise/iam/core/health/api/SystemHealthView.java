package com.enterprise.iam.core.health.api;

import com.enterprise.iam.core.shared.api.health.ComponentHealth;
import java.util.List;

/** Aggregated platform health (spec §62, OpenAPI SystemHealth). */
public record SystemHealthView(ComponentHealth.Status status, List<ComponentHealth> components) {
}
