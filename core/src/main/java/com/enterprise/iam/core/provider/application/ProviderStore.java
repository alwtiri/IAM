package com.enterprise.iam.core.provider.application;

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
}
