package com.enterprise.iam.core.sod.api;

import java.util.List;

/** Checks a requested role against the roles an identity already holds (spec §24). */
public interface SodChecker {

    List<SodConflict> check(List<String> heldRoles, String requestedRole);
}
