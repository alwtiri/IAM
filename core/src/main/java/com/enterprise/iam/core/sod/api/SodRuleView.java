package com.enterprise.iam.core.sod.api;

import java.util.UUID;

public record SodRuleView(UUID id, String code, String name, String leftRole, String rightRole, String mode, String severity, boolean enabled) {
}
