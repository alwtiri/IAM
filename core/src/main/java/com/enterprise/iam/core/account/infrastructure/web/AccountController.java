package com.enterprise.iam.core.account.infrastructure.web;

import com.enterprise.iam.core.account.api.AccountFindingView;
import com.enterprise.iam.core.account.api.AccountView;
import com.enterprise.iam.core.account.api.DiscoveryRunView;
import com.enterprise.iam.core.account.application.AccountService;
import com.enterprise.iam.core.account.application.AccountStore;
import com.enterprise.iam.core.account.domain.Account;
import com.enterprise.iam.core.account.domain.FindingRules;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.RequiresPermission;
import com.enterprise.iam.core.target.api.TargetDirectory;
import com.enterprise.iam.kernel.IamException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class AccountController {

    record GovernanceRequest(Account.Type type, UUID ownerIdentityId, UUID linkedIdentityId, Account.GovernanceState governanceState,
                             @Size(max = 500) String reason) {
    }

    record ResolveRequest(@NotBlank @Size(max = 500) String resolution) {
    }

    private final AccountService accounts;
    private final TargetDirectory targets;
    private final CurrentActorProvider actors;

    AccountController(AccountService accounts, TargetDirectory targets, CurrentActorProvider actors) {
        this.accounts = accounts;
        this.targets = targets;
        this.actors = actors;
    }

    @GetMapping("/accounts")
    @RequiresPermission(Permissions.ACCOUNT_READ)
    PageResult<AccountView> list(@RequestParam(required = false) UUID targetId,
                                 @RequestParam(required = false) Account.GovernanceState governanceState,
                                 @RequestParam(required = false) Boolean privileged,
                                 @RequestParam(required = false) @Size(max = 100) String search,
                                 @RequestParam(required = false) FindingRules.Type findingType,
                                 @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        var filter = new AccountStore.ListFilter(targetId, governanceState == null ? null : governanceState.name(), privileged, search,
                findingType == null ? null : findingType.name());
        return accounts.list(actors.require(), filter, PageRequest.of(limit, cursor));
    }

    @GetMapping("/accounts/{id}")
    @RequiresPermission(Permissions.ACCOUNT_READ)
    AccountView get(@PathVariable UUID id) {
        return accounts.get(actors.require(), id);
    }

    @PatchMapping("/accounts/{id}/governance")
    @RequiresPermission(Permissions.ACCOUNT_WRITE)
    AccountView governance(@PathVariable UUID id, @RequestHeader("If-Match") long version, @Valid @RequestBody GovernanceRequest r) {
        return accounts.changeGovernance(actors.require(), id,
                new AccountService.GovernanceChange(r.type(), r.ownerIdentityId(), r.linkedIdentityId(), r.governanceState(), r.reason()),
                version);
    }

    @GetMapping("/account-findings")
    @RequiresPermission(Permissions.ACCOUNT_READ)
    PageResult<AccountFindingView> findings(@RequestParam(required = false) FindingRules.Type type,
                                            @RequestParam(required = false) FindingRules.Severity severity,
                                            @RequestParam(defaultValue = "true") boolean open,
                                            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        return accounts.findings(actors.require(), type == null ? null : type.name(), severity == null ? null : severity.name(), open,
                PageRequest.of(limit, cursor));
    }

    @PostMapping("/account-findings/{id}:resolve")
    @RequiresPermission(Permissions.ACCOUNT_FINDING_RESOLVE)
    AccountFindingView resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest r) {
        return accounts.resolveFinding(actors.require(), id, r.resolution());
    }

    @GetMapping("/targets/{id}/discovery-runs")
    @RequiresPermission(Permissions.ACCOUNT_READ)
    List<DiscoveryRunView> discoveryRuns(@PathVariable UUID id) {
        var scope = targets.scopeOf(id).orElseThrow(() -> IamException.notFound("Target"));
        return accounts.discoveryRuns(actors.require(), id, scope);
    }
}
