package com.enterprise.iam.core.account.application;

import com.enterprise.iam.core.account.api.AccountFindingView;
import com.enterprise.iam.core.account.api.DiscoveryRunView;
import com.enterprise.iam.core.account.domain.Account;
import com.enterprise.iam.core.account.domain.FindingRules;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Persistence port of the account module. Scope attributes come from the target and provider instance. */
public interface AccountStore {

    /** Account plus the attributes needed for scope checks and display. */
    record Scoped(Account account, String targetName, String orgUnitPath, String environment, String providerType) {
    }

    /** Where discovery results land: target + provider instance with their scope attributes. */
    record Binding(UUID targetId, UUID providerInstanceId, String orgUnitPath, String environment, String providerType) {
    }

    record ListFilter(UUID targetId, String governanceState, Boolean privileged, String search, String findingType) {
    }

    record FindingScoped(AccountFindingView finding, String orgUnitPath, String environment, String providerType, UUID providerInstanceId) {
    }

    void insert(Account a);

    boolean update(Account a, long expectedVersion);

    Optional<Scoped> find(UUID id);

    Map<String, Account> findByNativeIds(UUID targetId, UUID providerInstanceId, Collection<String> nativeIds);

    List<Account> findNotSeenSince(UUID targetId, UUID providerInstanceId, Instant before);

    List<Scoped> list(ScopeFilter filter, ListFilter f, PageRequest page);

    Optional<Binding> binding(UUID targetId, UUID providerInstanceId);

    // findings
    Map<FindingRules.Type, UUID> openFindings(UUID accountId);

    void openFinding(UUID id, UUID accountId, FindingRules.Type type, FindingRules.Severity severity, Instant at, Map<String, String> details);

    void autoResolveFindings(UUID accountId, Set<FindingRules.Type> types, Instant at);

    Optional<FindingScoped> findFinding(UUID findingId);

    boolean resolveFinding(UUID findingId, UUID resolvedBy, String resolution, Instant at);

    List<FindingScoped> listFindings(ScopeFilter filter, String type, String severity, boolean openOnly, PageRequest page);

    // discovery runs
    void insertRun(UUID id, UUID providerInstanceId, UUID targetId, UUID operationId, Instant startedAt);

    Optional<DiscoveryRunView> findRun(UUID id);

    void addRunCounts(UUID id, int seen, int created, int groups);

    void finishRun(UUID id, String status, int removed, String errorMessage, Instant at);

    boolean hasCompletedRun(UUID targetId, UUID providerInstanceId, UUID exceptRunId);

    List<DiscoveryRunView> runs(UUID targetId, int limit);
}
