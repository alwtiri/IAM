package com.enterprise.iam.core.request.application;

import com.enterprise.iam.core.request.domain.AccessRequest;
import com.enterprise.iam.core.request.domain.ApprovalStep;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RequestStore {

    void insert(AccessRequest request, List<ApprovalStep> steps);

    Optional<AccessRequest> find(UUID id);

    List<ApprovalStep> steps(UUID requestId);

    boolean update(AccessRequest request, long expectedVersion);

    void updateStep(ApprovalStep step);

    boolean hasOpenRequest(UUID beneficiaryId, UUID roleId);

    List<AccessRequest> byRequester(UUID requesterId, int limit);

    List<AccessRequest> all(String status, int limit);

    /** Open requests whose current (PENDING) step targets this identity directly or one of its roles. */
    List<AccessRequest> pendingFor(UUID identityId, List<String> roleCodes, int limit);

    Optional<AccessRequest> byAssignment(UUID roleAssignmentId);
}
