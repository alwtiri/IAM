package com.enterprise.iam.provider.spi;

/**
 * Status of a capability for a given target (spec §18, gate clarification G5).
 *
 * <p>A provider descriptor declares {@link #SUPPORTED}, {@link #UNSUPPORTED} or {@link #AGENT_REQUIRED}.
 * {@link #UNAVAILABLE} and {@link #DEGRADED} are runtime states computed by the Core from provider,
 * agent, and gateway health.
 */
public enum CapabilityStatus {
    /** Implemented and usable. */
    SUPPORTED,
    /** Not implemented by this provider or not possible on this target type. Calls return UNSUPPORTED_CAPABILITY. */
    UNSUPPORTED,
    /** Implemented, but currently not usable (provider down, circuit open, dependency unavailable). */
    UNAVAILABLE,
    /** Usable with reduced function or reliability; the reason explains what is affected. */
    DEGRADED,
    /** Only available when an agent is installed and ACTIVE on the target. */
    AGENT_REQUIRED
}
