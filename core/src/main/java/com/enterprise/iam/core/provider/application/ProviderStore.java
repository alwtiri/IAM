package com.enterprise.iam.core.provider.application;

import com.enterprise.iam.core.provider.api.ProviderBindingView;
import com.enterprise.iam.core.provider.api.ProviderInstanceView;
import com.enterprise.iam.core.provider.domain.ProviderInstance;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProviderStore {

    void insert(ProviderInstance p);

    boolean setEnabled(UUID id, boolean enabled, long expectedVersion);

    Optional<ProviderInstance> find(UUID id);

    Optional<ProviderInstanceView> view(UUID id);

    boolean nameExists(String name);

    List<ProviderInstanceView> list(ScopeFilter filter, String type, PageRequest page);

    /** @return false if the binding already existed */
    boolean bind(UUID targetId, UUID providerInstanceId, String channel);

    boolean unbind(UUID targetId, UUID providerInstanceId);

    List<ProviderBindingView> bindings(UUID targetId);

    default List<ProviderBindingView> allBindings() {
        return List.of();
    }

    boolean isBound(UUID targetId, UUID providerInstanceId);
}
