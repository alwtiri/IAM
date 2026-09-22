package com.enterprise.iam.core.secrets.api;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Single-use credential handles for the worker plane (ADR-0005, PHASE-3-DESIGN §3): the command message carries only
 * opaque handles; the worker redeems each one just in time over mTLS. Issued in the caller's transaction, after the
 * operation row exists.
 */
public interface CredentialHandles {

    /**
     * @param purposes purpose (e.g. {@code connection}, {@code new}) → Vault reference of the secret to release
     * @param ttl      lifetime, at most 15 minutes (DB constraint); default 60 s is used when null
     * @return purpose → opaque handle
     */
    Map<String, String> issue(UUID operationId, String providerType, Map<String, SecretRef> purposes, Duration ttl);
}
