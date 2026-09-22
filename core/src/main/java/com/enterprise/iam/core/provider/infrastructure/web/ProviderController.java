package com.enterprise.iam.core.provider.infrastructure.web;

import org.springframework.web.bind.annotation.DeleteMapping;
import java.util.List;
import jakarta.validation.constraints.NotNull;
import com.enterprise.iam.core.provider.api.ProviderBindingView;
import com.enterprise.iam.core.provider.api.ProviderInstanceView;
import com.enterprise.iam.core.provider.application.ProviderRegistryService;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.RequiresPermission;
import com.enterprise.iam.core.shared.api.security.RequiresStepUp;
import com.enterprise.iam.kernel.Secret;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class ProviderController {

    /** The credential is write-only: it is moved into a {@link Secret} immediately and never echoed back. */
    record RegisterRequest(@NotBlank @Size(max = 48) String type, @NotBlank @Size(max = 100) String name,
                           @NotBlank @Size(max = 500) String endpoint, @Size(max = 50) Map<String, String> settings,
                           @Size(max = 16384) String credential) {
        @Override
        public String toString() {
            return "RegisterRequest[type=" + type + ", name=" + name + ", credential=REDACTED]";
        }
    }

    private final ProviderRegistryService providers;
    private final CurrentActorProvider actors;

    ProviderController(ProviderRegistryService providers, CurrentActorProvider actors) {
        this.providers = providers;
        this.actors = actors;
    }

    @GetMapping("/provider-instances")
    @RequiresPermission(Permissions.PROVIDER_READ)
    PageResult<ProviderInstanceView> list(@RequestParam(required = false) String type, @RequestParam(required = false) Integer limit,
                                          @RequestParam(required = false) String cursor) {
        return providers.list(actors.require(), type, PageRequest.of(limit, cursor));
    }

    @PostMapping("/provider-instances")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresPermission(Permissions.PROVIDER_WRITE)
    @RequiresStepUp
    ProviderInstanceView register(@Valid @RequestBody RegisterRequest r) {
        try (Secret credential = r.credential() == null || r.credential().isEmpty() ? null : Secret.of(r.credential())) {
            return providers.register(actors.require(),
                    new ProviderRegistryService.RegisterCommand(r.type(), r.name(), r.endpoint(), r.settings(), credential));
        }
    }

    @GetMapping("/provider-instances/{id}")
    @RequiresPermission(Permissions.PROVIDER_READ)
    ProviderInstanceView get(@PathVariable UUID id) {
        return providers.get(actors.require(), id);
    }

    @PostMapping("/provider-instances/{id}:enable")
    @RequiresPermission(Permissions.PROVIDER_WRITE)
    ProviderInstanceView enable(@PathVariable UUID id) {
        return providers.setEnabled(actors.require(), id, true);
    }

    @PostMapping("/provider-instances/{id}:disable")
    @RequiresPermission(Permissions.PROVIDER_WRITE)
    ProviderInstanceView disable(@PathVariable UUID id) {
        return providers.setEnabled(actors.require(), id, false);
    }

    record BindRequest(@NotNull UUID providerInstanceId, @Size(max = 32) String channel) {
    }

    @GetMapping("/targets/{id}/provider-bindings")
    @RequiresPermission(Permissions.TARGET_READ)
    List<ProviderBindingView> bindings(@PathVariable UUID id) {
        return providers.bindings(actors.require(), id);
    }

    @PostMapping("/targets/{id}/provider-bindings")
    @RequiresPermission(Permissions.PROVIDER_WRITE)
    List<ProviderBindingView> bind(@PathVariable UUID id, @Valid @RequestBody BindRequest r) {
        return providers.bind(actors.require(), id, r.providerInstanceId(), r.channel());
    }

    @DeleteMapping("/targets/{id}/provider-bindings/{providerInstanceId}")
    @RequiresPermission(Permissions.PROVIDER_WRITE)
    List<ProviderBindingView> unbind(@PathVariable UUID id, @PathVariable UUID providerInstanceId) {
        return providers.unbind(actors.require(), id, providerInstanceId);
    }

    @GetMapping("/capabilities/catalog")
    @RequiresPermission(Permissions.SYSTEM_READ)
    ProviderRegistryService.CapabilityCatalog catalog() {
        return providers.catalog(actors.require());
    }
}
