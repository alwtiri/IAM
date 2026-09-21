package com.enterprise.iam.provider.spi.result;

import com.enterprise.iam.provider.spi.Capability;
import com.enterprise.iam.provider.spi.ProviderOperation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Result of a provider operation. Invariants enforced at construction:
 * <ul>
 *   <li>A SUCCEEDED result of a mutating operation carries a {@link Verification} (gate clarification G5).</li>
 *   <li>Every non-SUCCEEDED result carries a {@link ProviderError}.</li>
 *   <li>UNSUPPORTED results use error code {@code UNSUPPORTED_CAPABILITY} and name the capability.</li>
 * </ul>
 *
 * @param <T> payload type for successful results
 */
public final class OperationResult<T> {

    public static final String UNSUPPORTED_CAPABILITY = "UNSUPPORTED_CAPABILITY";
    public static final String SECRETS_UNAVAILABLE = "SECRETS_UNAVAILABLE";

    private final ProviderOperation operation;
    private final OperationOutcome outcome;
    private final T value;
    private final Verification verification;
    private final ProviderError error;
    private final List<String> details;

    private OperationResult(ProviderOperation operation, OperationOutcome outcome, T value,
                            Verification verification, ProviderError error, List<String> details) {
        this.operation = Objects.requireNonNull(operation, "operation");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.value = value;
        this.verification = verification;
        this.error = error;
        this.details = details == null ? List.of() : List.copyOf(details);
        if (outcome == OperationOutcome.SUCCEEDED) {
            if (operation.mutating() && verification == null) {
                throw new IllegalStateException(
                        "SUCCEEDED requires a verification for mutating operation " + operation);
            }
            if (error != null) {
                throw new IllegalStateException("SUCCEEDED result must not carry an error");
            }
        } else if (error == null) {
            throw new IllegalStateException(outcome + " result must carry an error");
        }
    }

    /** Verified success of a mutating operation. */
    public static <T> OperationResult<T> succeeded(ProviderOperation operation, T value, Verification verification) {
        Objects.requireNonNull(verification, "verification");
        return new OperationResult<>(operation, OperationOutcome.SUCCEEDED, value, verification, null, null);
    }

    /** Success of a non-mutating (read/discovery) operation. */
    public static <T> OperationResult<T> read(ProviderOperation operation, T value) {
        if (operation.mutating()) {
            throw new IllegalArgumentException(operation + " is mutating; use succeeded(..., verification)");
        }
        return new OperationResult<>(operation, OperationOutcome.SUCCEEDED, value, null, null, null);
    }

    public static <T> OperationResult<T> failed(ProviderOperation operation, ProviderError error) {
        return new OperationResult<>(operation, OperationOutcome.FAILED, null, null, error, null);
    }

    public static <T> OperationResult<T> partial(ProviderOperation operation, ProviderError error, List<String> details) {
        return new OperationResult<>(operation, OperationOutcome.PARTIAL, null, null, error, details);
    }

    public static <T> OperationResult<T> timeout(ProviderOperation operation, String message) {
        return new OperationResult<>(operation, OperationOutcome.TIMEOUT, null, null,
                new ProviderError("OPERATION_TIMEOUT", message, true, true), null);
    }

    public static <T> OperationResult<T> unknown(ProviderOperation operation, String message) {
        return new OperationResult<>(operation, OperationOutcome.UNKNOWN, null, null,
                new ProviderError("OUTCOME_UNVERIFIED", message, false, true), null);
    }

    /** The provider does not implement the capability required by {@code operation}. */
    public static <T> OperationResult<T> unsupported(ProviderOperation operation, String explanation) {
        Capability capability = operation.requiredCapability();
        return new OperationResult<>(operation, OperationOutcome.UNSUPPORTED, null, null,
                new ProviderError(UNSUPPORTED_CAPABILITY,
                        "Capability " + capability + " is not supported: " + explanation, false, false),
                null);
    }

    /** Secret material could not be obtained; fail closed without touching the target. */
    public static <T> OperationResult<T> secretsUnavailable(ProviderOperation operation) {
        return new OperationResult<>(operation, OperationOutcome.FAILED, null, null,
                new ProviderError(SECRETS_UNAVAILABLE, "Required secret material is unavailable", true, false), null);
    }

    public ProviderOperation operation() {
        return operation;
    }

    public OperationOutcome outcome() {
        return outcome;
    }

    public Optional<T> value() {
        return Optional.ofNullable(value);
    }

    public Optional<Verification> verification() {
        return Optional.ofNullable(verification);
    }

    public Optional<ProviderError> error() {
        return Optional.ofNullable(error);
    }

    public List<String> details() {
        return details;
    }

    public boolean isSuccess() {
        return outcome == OperationOutcome.SUCCEEDED;
    }

    @Override
    public String toString() {
        return "OperationResult[" + operation + " " + outcome
                + (error != null ? " " + error.code() : "") + "]";
    }
}
