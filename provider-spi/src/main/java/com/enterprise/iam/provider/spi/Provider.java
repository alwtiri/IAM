package com.enterprise.iam.provider.spi;

import com.enterprise.iam.provider.spi.model.AccountRef;
import com.enterprise.iam.provider.spi.model.AccountSpec;
import com.enterprise.iam.provider.spi.model.AccountState;
import com.enterprise.iam.provider.spi.model.ConnectionReport;
import com.enterprise.iam.provider.spi.model.DesiredState;
import com.enterprise.iam.provider.spi.model.DiscoveredGroup;
import com.enterprise.iam.provider.spi.model.DiscoveredTarget;
import com.enterprise.iam.provider.spi.model.DiscoverySummary;
import com.enterprise.iam.provider.spi.model.GroupRef;
import com.enterprise.iam.provider.spi.model.GroupSpec;
import com.enterprise.iam.provider.spi.model.ObjectRef;
import com.enterprise.iam.provider.spi.model.Page;
import com.enterprise.iam.provider.spi.model.PasswordChange;
import com.enterprise.iam.provider.spi.model.ReconciliationReport;
import com.enterprise.iam.provider.spi.result.OperationResult;
import java.util.UUID;

/**
 * A provider bound to one provider instance (spec §11). Instances are created by a {@link ProviderFactory}
 * and executed only inside worker pools (ADR-0010), each call under the pool's timeout, bulkhead and
 * circuit breaker.
 *
 * <p><b>Honest defaults.</b> Every operation except {@link #descriptor()} has a default implementation that
 * returns {@link OperationResult#unsupported}. A provider overrides exactly the operations whose capability it
 * declares {@link CapabilityStatus#SUPPORTED}; the contract test kit fails if a declared capability is not
 * overridden or an undeclared one is.
 *
 * <p>Implementations must not throw for expected failures (connectivity, authentication, not found); they return
 * a FAILED/TIMEOUT/UNKNOWN result with a {@link com.enterprise.iam.provider.spi.result.ProviderError}. Unchecked
 * exceptions are treated by the worker as UNKNOWN for mutating operations and FAILED for reads.
 */
public interface Provider {

    ProviderDescriptor descriptor();

    default OperationResult<ConnectionReport> validateConnection(OperationContext ctx) {
        return notImplemented(ProviderOperation.VALIDATE_CONNECTION);
    }

    /** Full read-only inventory pass. Must not modify the target. */
    default OperationResult<DiscoverySummary> discover(OperationContext ctx) {
        return notImplemented(ProviderOperation.DISCOVER);
    }

    default OperationResult<Page<AccountState>> discoverAccounts(OperationContext ctx, String cursor) {
        return notImplemented(ProviderOperation.DISCOVER_ACCOUNTS);
    }

    default OperationResult<Page<DiscoveredGroup>> discoverGroups(OperationContext ctx, String cursor) {
        return notImplemented(ProviderOperation.DISCOVER_GROUPS);
    }

    default OperationResult<Page<DiscoveredTarget>> discoverTargets(OperationContext ctx, String cursor) {
        return notImplemented(ProviderOperation.DISCOVER_TARGETS);
    }

    default OperationResult<AccountState> getAccountState(OperationContext ctx, AccountRef account) {
        return notImplemented(ProviderOperation.GET_ACCOUNT_STATE);
    }

    /** Idempotent: if the account already exists with matching properties, verify and succeed without duplicating. */
    default OperationResult<AccountState> createAccount(OperationContext ctx, AccountSpec spec) {
        return notImplemented(ProviderOperation.CREATE_ACCOUNT);
    }

    default OperationResult<AccountState> enableAccount(OperationContext ctx, AccountRef account) {
        return notImplemented(ProviderOperation.ENABLE_ACCOUNT);
    }

    default OperationResult<AccountState> disableAccount(OperationContext ctx, AccountRef account) {
        return notImplemented(ProviderOperation.DISABLE_ACCOUNT);
    }

    default OperationResult<AccountState> unlockAccount(OperationContext ctx, AccountRef account) {
        return notImplemented(ProviderOperation.UNLOCK_ACCOUNT);
    }

    default OperationResult<Void> deleteAccount(OperationContext ctx, AccountRef account) {
        return notImplemented(ProviderOperation.DELETE_ACCOUNT);
    }

    default OperationResult<Void> changePassword(OperationContext ctx, PasswordChange change) {
        return notImplemented(ProviderOperation.CHANGE_PASSWORD);
    }

    default OperationResult<Void> resetPassword(OperationContext ctx, PasswordChange change) {
        return notImplemented(ProviderOperation.RESET_PASSWORD);
    }

    /**
     * Sets the new (PENDING) secret on the target and verifies it, typically by login test. The Core promotes the
     * Vault version only after a SUCCEEDED result (ADR-0005). Never update Vault from the provider.
     */
    default OperationResult<Void> rotatePassword(OperationContext ctx, PasswordChange change) {
        return notImplemented(ProviderOperation.ROTATE_PASSWORD);
    }

    default OperationResult<DiscoveredGroup> createGroup(OperationContext ctx, GroupSpec spec) {
        return notImplemented(ProviderOperation.CREATE_GROUP);
    }

    default OperationResult<DiscoveredGroup> modifyGroup(OperationContext ctx, GroupRef group, GroupSpec spec) {
        return notImplemented(ProviderOperation.MODIFY_GROUP);
    }

    /** Idempotent: adding an existing membership verifies and succeeds. */
    default OperationResult<Void> addGroupMember(OperationContext ctx, GroupRef group, AccountRef member) {
        return notImplemented(ProviderOperation.ADD_GROUP_MEMBER);
    }

    default OperationResult<Void> removeGroupMember(OperationContext ctx, GroupRef group, AccountRef member) {
        return notImplemented(ProviderOperation.REMOVE_GROUP_MEMBER);
    }

    default OperationResult<Void> moveObject(OperationContext ctx, ObjectRef object, ObjectRef destination) {
        return notImplemented(ProviderOperation.MOVE_OBJECT);
    }

    /** Applies only the objects listed in {@code desired}; everything else is left untouched. */
    default OperationResult<ReconciliationReport> applyDesiredState(OperationContext ctx, DesiredState desired) {
        return notImplemented(ProviderOperation.APPLY_DESIRED_STATE);
    }

    /** Re-verifies the effect of an earlier operation whose outcome was UNKNOWN or TIMEOUT. Read-only. */
    default OperationResult<AccountState> verifyOperation(OperationContext ctx, UUID originalOperationId, AccountRef account) {
        return notImplemented(ProviderOperation.VERIFY_OPERATION);
    }

    /** Read-only desired-vs-actual comparison (spec §45). */
    default OperationResult<ReconciliationReport> reconcile(OperationContext ctx, DesiredState desired) {
        return notImplemented(ProviderOperation.RECONCILE);
    }

    private <T> OperationResult<T> notImplemented(ProviderOperation operation) {
        return OperationResult.unsupported(operation,
                descriptor().capability(operation.requiredCapability()).explanation() != null
                        ? descriptor().capability(operation.requiredCapability()).explanation()
                        : "operation " + operation + " is not implemented by provider type '" + descriptor().type() + "'");
    }
}
