package com.enterprise.iam.core.sod.domain;

import com.enterprise.iam.core.sod.api.SodConflict;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Two roles that must not be held by the same identity (symmetric). */
public record SodRule(UUID id, String code, String name, String leftRole, String rightRole, String mode, String severity, boolean enabled) {

    public Optional<SodConflict> conflict(List<String> held, String requested) {
        if (!enabled) {
            return Optional.empty();
        }
        if (requested.equals(leftRole) && held.contains(rightRole)) {
            return Optional.of(new SodConflict(code, name, rightRole, requested, mode, severity));
        }
        if (requested.equals(rightRole) && held.contains(leftRole)) {
            return Optional.of(new SodConflict(code, name, leftRole, requested, mode, severity));
        }
        return Optional.empty();
    }
}
