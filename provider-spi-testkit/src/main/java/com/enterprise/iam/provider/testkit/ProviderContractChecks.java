package com.enterprise.iam.provider.testkit;

import com.enterprise.iam.kernel.Secret;
import com.enterprise.iam.provider.spi.CapabilityDescriptor;
import com.enterprise.iam.provider.spi.CapabilityStatus;
import com.enterprise.iam.provider.spi.CredentialHandle;
import com.enterprise.iam.provider.spi.OperationContext;
import com.enterprise.iam.provider.spi.Provider;
import com.enterprise.iam.provider.spi.ProviderDescriptor;
import com.enterprise.iam.provider.spi.ProviderOperation;
import com.enterprise.iam.provider.spi.SpiVersion;
import com.enterprise.iam.provider.spi.model.AccountRef;
import com.enterprise.iam.provider.spi.model.AccountSpec;
import com.enterprise.iam.provider.spi.model.DesiredState;
import com.enterprise.iam.provider.spi.model.GroupRef;
import com.enterprise.iam.provider.spi.model.GroupSpec;
import com.enterprise.iam.provider.spi.model.ObjectRef;
import com.enterprise.iam.provider.spi.model.PasswordChange;
import com.enterprise.iam.provider.spi.result.OperationOutcome;
import com.enterprise.iam.provider.spi.result.OperationResult;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Framework-independent structural contract checks every provider must pass (ADR-0003).
 * {@link ProviderContractTest} wraps these checks for JUnit 5; they can also run standalone.
 *
 * <p>Checks:
 * <ol>
 *   <li>Descriptor present and built against a compatible SPI version.</li>
 *   <li>Every operation mapped to a SUPPORTED capability is overridden (no silent gaps).</li>
 *   <li>No operation mapped to a non-SUPPORTED capability is overridden (no hidden, undeclared capabilities).</li>
 *   <li>Calling any unsupported operation returns UNSUPPORTED with code UNSUPPORTED_CAPABILITY and never throws.</li>
 *   <li>Non-SUPPORTED capabilities carry an explanation.</li>
 * </ol>
 */
public final class ProviderContractChecks {

    /** Maps each SPI operation to the {@link Provider} method implementing it. */
    static final Map<ProviderOperation, String> METHOD_NAMES = new EnumMap<>(Map.ofEntries(
            Map.entry(ProviderOperation.VALIDATE_CONNECTION, "validateConnection"),
            Map.entry(ProviderOperation.DISCOVER, "discover"),
            Map.entry(ProviderOperation.DISCOVER_ACCOUNTS, "discoverAccounts"),
            Map.entry(ProviderOperation.DISCOVER_GROUPS, "discoverGroups"),
            Map.entry(ProviderOperation.DISCOVER_TARGETS, "discoverTargets"),
            Map.entry(ProviderOperation.GET_ACCOUNT_STATE, "getAccountState"),
            Map.entry(ProviderOperation.CREATE_ACCOUNT, "createAccount"),
            Map.entry(ProviderOperation.ENABLE_ACCOUNT, "enableAccount"),
            Map.entry(ProviderOperation.DISABLE_ACCOUNT, "disableAccount"),
            Map.entry(ProviderOperation.UNLOCK_ACCOUNT, "unlockAccount"),
            Map.entry(ProviderOperation.DELETE_ACCOUNT, "deleteAccount"),
            Map.entry(ProviderOperation.CHANGE_PASSWORD, "changePassword"),
            Map.entry(ProviderOperation.RESET_PASSWORD, "resetPassword"),
            Map.entry(ProviderOperation.ROTATE_PASSWORD, "rotatePassword"),
            Map.entry(ProviderOperation.CREATE_GROUP, "createGroup"),
            Map.entry(ProviderOperation.MODIFY_GROUP, "modifyGroup"),
            Map.entry(ProviderOperation.ADD_GROUP_MEMBER, "addGroupMember"),
            Map.entry(ProviderOperation.REMOVE_GROUP_MEMBER, "removeGroupMember"),
            Map.entry(ProviderOperation.MOVE_OBJECT, "moveObject"),
            Map.entry(ProviderOperation.APPLY_DESIRED_STATE, "applyDesiredState"),
            Map.entry(ProviderOperation.VERIFY_OPERATION, "verifyOperation"),
            Map.entry(ProviderOperation.RECONCILE, "reconcile")));

    private ProviderContractChecks() {
    }

    /** Runs all structural checks and returns the list of violations (empty = compliant). */
    public static List<String> run(Provider provider) {
        Objects.requireNonNull(provider, "provider");
        List<String> violations = new ArrayList<>();
        ProviderDescriptor descriptor = provider.descriptor();
        if (descriptor == null) {
            violations.add("descriptor() returned null");
            return violations;
        }
        if (!SpiVersion.isCompatible(descriptor.spiMajor(), descriptor.spiMinor())) {
            violations.add("SPI version " + descriptor.spiMajor() + "." + descriptor.spiMinor()
                    + " is not compatible with runtime " + SpiVersion.current());
        }
        for (CapabilityDescriptor cd : descriptor.capabilities().values()) {
            if (cd.status() != CapabilityStatus.SUPPORTED && (cd.explanation() == null || cd.explanation().isBlank())) {
                violations.add(cd.capability() + " is " + cd.status() + " without explanation");
            }
        }
        OperationContext ctx = probeContext();
        for (ProviderOperation op : ProviderOperation.values()) {
            boolean supported = descriptor.supports(op.requiredCapability());
            boolean overridden = isOverridden(provider.getClass(), METHOD_NAMES.get(op));
            if (supported && !overridden) {
                violations.add(op + " requires SUPPORTED capability " + op.requiredCapability()
                        + " but is not implemented (default UNSUPPORTED would be returned)");
            }
            if (!supported && overridden) {
                violations.add(op + " is implemented but capability " + op.requiredCapability()
                        + " is not declared SUPPORTED (hidden capability)");
            }
            if (!supported) {
                checkUnsupportedCall(provider, op, ctx, violations);
            }
        }
        return violations;
    }

    private static void checkUnsupportedCall(Provider provider, ProviderOperation op, OperationContext ctx,
                                             List<String> violations) {
        try {
            Method m = findMethod(METHOD_NAMES.get(op));
            Object[] args = probeArguments(m, ctx);
            Object r = m.invoke(provider, args);
            if (!(r instanceof OperationResult<?> result)) {
                violations.add(op + " returned null instead of an OperationResult");
                return;
            }
            if (result.outcome() != OperationOutcome.UNSUPPORTED
                    || result.error().map(e -> !OperationResult.UNSUPPORTED_CAPABILITY.equals(e.code())).orElse(true)) {
                violations.add(op + " is unsupported but returned " + result);
            }
        } catch (InvocationTargetException e) {
            violations.add(op + " threw " + e.getCause().getClass().getSimpleName()
                    + " instead of returning UNSUPPORTED_CAPABILITY");
        } catch (ReflectiveOperationException e) {
            violations.add("contract kit could not invoke " + op + ": " + e.getMessage());
        }
    }

    static boolean isOverridden(Class<?> implementation, String methodName) {
        for (Class<?> c = implementation; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && !m.isSynthetic() && !m.isBridge()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Method findMethod(String name) throws NoSuchMethodException {
        for (Method m : Provider.class.getMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static Object[] probeArguments(Method m, OperationContext ctx) {
        Class<?>[] types = m.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            Class<?> t = types[i];
            if (t == OperationContext.class) {
                args[i] = ctx;
            } else if (t == AccountRef.class) {
                args[i] = new AccountRef(null, "contract-probe");
            } else if (t == AccountSpec.class) {
                args[i] = new AccountSpec("contract-probe", null, null, null, null);
            } else if (t == GroupRef.class) {
                args[i] = new GroupRef(null, "contract-probe");
            } else if (t == GroupSpec.class) {
                args[i] = new GroupSpec("contract-probe", null, null);
            } else if (t == ObjectRef.class) {
                args[i] = new ObjectRef("probe", "contract-probe");
            } else if (t == PasswordChange.class) {
                args[i] = new PasswordChange(new AccountRef(null, "contract-probe"), null, new CredentialHandle("probe"));
            } else if (t == DesiredState.class) {
                args[i] = new DesiredState(null, null);
            } else if (t == UUID.class) {
                args[i] = UUID.randomUUID();
            } else {
                args[i] = null; // e.g. discovery cursor
            }
        }
        return args;
    }

    /** Context whose credential resolver always refuses: contract probes must never need real secrets. */
    public static OperationContext probeContext() {
        return new OperationContext(UUID.randomUUID(), "contract-probe-" + UUID.randomUUID(), "contract-probe-corr",
                1, Instant.now().plus(Duration.ofSeconds(30)), handle -> {
                    throw new com.enterprise.iam.provider.spi.CredentialResolver.SecretsUnavailableException(
                            "contract probe: no secrets available");
                });
    }

    /** Helper for providers' own tests: a resolver returning a fixed secret. */
    public static OperationContext contextWithSecret(String secretValue) {
        return new OperationContext(UUID.randomUUID(), "test-" + UUID.randomUUID(), "test-corr-0001",
                1, Instant.now().plus(Duration.ofSeconds(30)), handle -> Secret.of(secretValue));
    }
}
