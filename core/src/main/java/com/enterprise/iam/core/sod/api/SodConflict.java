package com.enterprise.iam.core.sod.api;

/** A separation-of-duties rule that a role combination violates. PREVENTIVE conflicts block; DETECTIVE ones are reported. */
public record SodConflict(String ruleCode, String ruleName, String heldRole, String requestedRole, String mode, String severity) {

    public boolean blocking() {
        return "PREVENTIVE".equals(mode);
    }
}
