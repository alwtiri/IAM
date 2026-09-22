package com.enterprise.iam.core.policy.application;

import com.enterprise.iam.core.policy.domain.Policy;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyStore {

    List<Policy> all();

    Optional<Policy> find(UUID id);

    boolean setEnabled(UUID id, boolean enabled, long expectedVersion);
}
