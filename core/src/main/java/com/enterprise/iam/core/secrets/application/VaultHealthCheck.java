package com.enterprise.iam.core.secrets.application;

import com.enterprise.iam.core.shared.api.health.ComponentHealth;
import com.enterprise.iam.core.shared.api.health.ComponentHealthCheck;
import java.util.List;

public class VaultHealthCheck implements ComponentHealthCheck {

    private final VaultClient vault;

    public VaultHealthCheck(VaultClient vault) {
        this.vault = vault;
    }

    @Override
    public String component() {
        return "vault";
    }

    @Override
    public ComponentHealth.Category category() {
        return ComponentHealth.Category.SECRETS;
    }

    @Override
    public ComponentHealth.Classification classification() {
        return ComponentHealth.Classification.CORE_DEPENDENCY;
    }

    @Override
    public List<String> affectedFunctionality() {
        return List.of("Storing and using credentials (provider registration, rotation, secret release) fails closed with "
                + "SECRETS_UNAVAILABLE; identity, governance, audit and reporting continue");
    }

    @Override
    public Result check() {
        return switch (vault.health()) {
            case ACTIVE -> Result.healthy();
            case STANDBY -> Result.degraded("standby node");
            case SEALED -> Result.unavailable("sealed");
            case UNINITIALIZED -> Result.unavailable("not initialized");
            case UNREACHABLE -> Result.unavailable("unreachable");
        };
    }
}
