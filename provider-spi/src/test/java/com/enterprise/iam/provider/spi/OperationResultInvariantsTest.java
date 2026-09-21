package com.enterprise.iam.provider.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.provider.spi.result.OperationOutcome;
import com.enterprise.iam.provider.spi.result.OperationResult;
import com.enterprise.iam.provider.spi.result.Verification;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationResultInvariantsTest {

    @Test
    void mutatingSuccessRequiresVerification() {
        assertThrows(NullPointerException.class,
                () -> OperationResult.succeeded(ProviderOperation.DISABLE_ACCOUNT, "x", null));
        assertThrows(IllegalArgumentException.class,
                () -> OperationResult.read(ProviderOperation.DISABLE_ACCOUNT, "x"));
    }

    @Test
    void notPossibleIsNotAVerification() {
        assertThrows(IllegalArgumentException.class,
                () -> new Verification(VerificationMode.NOT_POSSIBLE, Instant.now(), "n/a"));
    }

    @Test
    void verifiedSuccessIsSuccess() {
        OperationResult<String> r = OperationResult.succeeded(ProviderOperation.DISABLE_ACCOUNT, "x",
                new Verification(VerificationMode.READ_BACK, Instant.now(), "status=DISABLED"));
        assertTrue(r.isSuccess());
        assertTrue(r.verification().isPresent());
    }

    @Test
    void unsupportedCarriesUnsupportedCapabilityCode() {
        OperationResult<Void> r = OperationResult.unsupported(ProviderOperation.ROTATE_PASSWORD, "no API");
        assertEquals(OperationOutcome.UNSUPPORTED, r.outcome());
        assertEquals(OperationResult.UNSUPPORTED_CAPABILITY, r.error().orElseThrow().code());
        assertFalse(r.error().orElseThrow().changeMayHaveApplied());
    }

    @Test
    void secretsUnavailableFailsClosedWithoutChange() {
        OperationResult<Void> r = OperationResult.secretsUnavailable(ProviderOperation.ROTATE_PASSWORD);
        assertEquals(OperationOutcome.FAILED, r.outcome());
        assertEquals(OperationResult.SECRETS_UNAVAILABLE, r.error().orElseThrow().code());
        assertFalse(r.error().orElseThrow().changeMayHaveApplied());
    }

    @Test
    void runtimeStatesCannotBeDeclared() {
        assertThrows(IllegalArgumentException.class, () -> new CapabilityDescriptor(
                Capability.ACCOUNT_CREATE, CapabilityStatus.UNAVAILABLE, null, "down"));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityDescriptor(
                Capability.ACCOUNT_CREATE, CapabilityStatus.UNSUPPORTED, null, " "));
    }

    @Test
    void undeclaredCapabilityIsUnsupportedWithExplanation() {
        ProviderDescriptor d = ProviderDescriptor.builder(ProviderTypeId.of("linux"), "1.0").build();
        assertFalse(d.supports(Capability.ACCOUNT_CREATE));
        assertTrue(d.capability(Capability.ACCOUNT_CREATE).explanation().contains("linux"));
    }

    @Test
    void connectionSettingsRejectSecretLikeKeys() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderConnection(UUID.randomUUID(),
                ProviderTypeId.of("linux"), "ssh://host", Map.of("adminPassword", "x"), null));
    }

    @Test
    void providerTypeIdNamesIsolatedQueues() {
        ProviderTypeId t = ProviderTypeId.of("hpe-3par");
        assertEquals("ops.hpe-3par", t.operationQueue());
        assertEquals("ops.discovery.hpe-3par", t.discoveryQueue());
        assertThrows(IllegalArgumentException.class, () -> ProviderTypeId.of("Bad Type"));
    }

    @Test
    void spiCompatibility() {
        assertTrue(SpiVersion.isCompatible(1, 0));
        assertFalse(SpiVersion.isCompatible(2, 0));
        assertFalse(SpiVersion.isCompatible(1, SpiVersion.MINOR + 1));
    }
}
