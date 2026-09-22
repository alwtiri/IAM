package com.enterprise.iam.core.account.infrastructure.web;

import com.enterprise.iam.core.account.api.CheckoutView;
import com.enterprise.iam.core.account.api.EmergencyUseView;
import com.enterprise.iam.core.account.api.RevealedCredential;
import com.enterprise.iam.core.account.api.VaultedCredentialView;
import com.enterprise.iam.core.account.application.CredentialVaultService;
import com.enterprise.iam.core.shared.api.security.AuthenticatedEndpoint;
import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.RequiresPermission;
import com.enterprise.iam.core.shared.api.security.RequiresStepUp;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Privileged credential vault and checkouts (ADR-0021). Revealing a password always needs step-up and is audited. */
@RestController
@RequestMapping("/api/v1")
class CredentialVaultController {

    record ReasonRequest(@Size(max = 500) String reason) {
    }

    record EmergencyFlag(boolean emergency) {
    }

    record BreakGlassRequest(@NotBlank @Size(min = 10, max = 500) String reason) {
    }

    record ReviewRequest(@NotBlank @Size(max = 1000) String note) {
    }

    record CheckoutRequest(@NotBlank @Size(max = 500) String reason, @Min(1) @Max(72) int durationHours) {
    }

    private final CredentialVaultService vault;
    private final CurrentActorProvider actors;

    CredentialVaultController(CredentialVaultService vault, CurrentActorProvider actors) {
        this.vault = vault;
        this.actors = actors;
    }

    @GetMapping("/vaulted-credentials")
    @RequiresPermission(Permissions.ACCOUNT_READ)
    List<VaultedCredentialView> list() {
        return vault.list(actors.require());
    }

    @GetMapping("/vaulted-credentials/{accountId}")
    @RequiresPermission(Permissions.ACCOUNT_READ)
    VaultedCredentialView get(@PathVariable UUID accountId) {
        return vault.get(actors.require(), accountId);
    }

    @PostMapping("/accounts/{accountId}:vault")
    @RequiresPermission(Permissions.CREDENTIAL_MANAGE)
    @RequiresStepUp
    VaultedCredentialView manage(@PathVariable UUID accountId, @Valid @RequestBody(required = false) ReasonRequest r) {
        return vault.manage(actors.require(), accountId, r == null ? null : r.reason());
    }

    @PostMapping("/vaulted-credentials/{accountId}:rotate")
    @RequiresPermission(Permissions.CREDENTIAL_MANAGE)
    @RequiresStepUp
    VaultedCredentialView rotate(@PathVariable UUID accountId, @Valid @RequestBody(required = false) ReasonRequest r) {
        return vault.rotate(actors.require(), accountId, r == null ? null : r.reason());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/vaulted-credentials/{accountId}")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    @RequiresPermission(Permissions.CREDENTIAL_MANAGE)
    @RequiresStepUp
    void unmanage(@PathVariable UUID accountId, @Valid @RequestBody(required = false) ReasonRequest r) {
        vault.unmanage(actors.require(), accountId, r == null ? null : r.reason());
    }

    @PostMapping("/vaulted-credentials/{accountId}:checkout")
    @RequiresPermission(Permissions.CREDENTIAL_MANAGE)
    @RequiresStepUp
    CheckoutView checkoutDirect(@PathVariable UUID accountId, @Valid @RequestBody CheckoutRequest r) {
        return vault.checkoutDirect(actors.require(), accountId, r.durationHours(), r.reason());
    }

    @PostMapping("/vaulted-credentials/{accountId}:mark-emergency")
    @RequiresPermission(Permissions.CREDENTIAL_MANAGE)
    VaultedCredentialView markEmergency(@PathVariable UUID accountId, @RequestBody EmergencyFlag r) {
        return vault.setEmergency(actors.require(), accountId, r.emergency());
    }

    @PostMapping("/vaulted-credentials/{accountId}:break-glass")
    @RequiresPermission(Permissions.EMERGENCY_ACCESS)
    @RequiresStepUp
    CheckoutView breakGlass(@PathVariable UUID accountId, @Valid @RequestBody BreakGlassRequest r) {
        return vault.breakGlass(actors.require(), accountId, r.reason());
    }

    @GetMapping("/emergency-uses")
    @RequiresPermission(Permissions.EMERGENCY_REVIEW)
    List<EmergencyUseView> emergencyUses(@RequestParam(defaultValue = "false") boolean pending) {
        return vault.emergencyUses(actors.require(), pending);
    }

    @PostMapping("/emergency-uses/{checkoutId}:review")
    @RequiresPermission(Permissions.EMERGENCY_REVIEW)
    void review(@PathVariable UUID checkoutId, @Valid @RequestBody ReviewRequest r) {
        vault.reviewEmergency(actors.require(), checkoutId, r.note());
    }

    @GetMapping("/credential-checkouts")
    @AuthenticatedEndpoint
    List<CheckoutView> checkouts(@RequestParam(defaultValue = "true") boolean mine) {
        return mine ? vault.myCheckouts(actors.require()) : vault.allCheckouts(actors.require());
    }

    @PostMapping("/credential-checkouts/{id}:reveal")
    @AuthenticatedEndpoint
    @RequiresStepUp
    ResponseEntity<RevealedCredential> reveal(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(vault.reveal(actors.require(), id));
    }

    @PostMapping("/credential-checkouts/{id}:check-in")
    @AuthenticatedEndpoint
    CheckoutView checkIn(@PathVariable UUID id) {
        return vault.checkIn(actors.require(), id);
    }
}
