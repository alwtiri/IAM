package com.enterprise.iam.core.authorization.api;

import java.util.List;
import java.util.UUID;

public record RoleView(UUID id, String code, String name, String description, boolean builtIn, List<String> permissions) {
}
