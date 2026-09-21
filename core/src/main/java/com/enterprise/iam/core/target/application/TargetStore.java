package com.enterprise.iam.core.target.application;

import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.security.ScopeFilter;
import com.enterprise.iam.core.target.domain.Target;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TargetStore {

    record Scoped(Target target, String orgUnitPath) {
    }

    void insert(Target t);

    boolean update(Target t, long expectedVersion);

    Optional<Scoped> find(UUID id);

    boolean nameExists(String name, UUID exceptId);

    List<Scoped> list(ScopeFilter filter, String type, String environment, PageRequest page);
}
