package com.enterprise.iam.core.audit.infrastructure.web;

import com.enterprise.iam.core.audit.api.AuditEventView;
import com.enterprise.iam.core.audit.api.AuditSearch;
import com.enterprise.iam.core.audit.api.AuditVerificationResult;
import com.enterprise.iam.core.audit.application.AuditService;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import com.enterprise.iam.core.shared.api.paging.PageResult;
import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.core.shared.api.security.Permissions;
import com.enterprise.iam.core.shared.api.security.RequiresPermission;
import com.enterprise.iam.core.shared.api.security.RequiresStepUp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class AuditController {

    private final AuditService audit;
    private final CurrentActorProvider actors;

    AuditController(AuditService audit, CurrentActorProvider actors) {
        this.audit = audit;
        this.actors = actors;
    }

    @GetMapping("/audit-events")
    @RequiresPermission(Permissions.AUDIT_READ)
    PageResult<AuditEventView> search(@RequestParam(required = false) UUID actorIdentityId,
                                      @RequestParam(required = false) String action,
                                      @RequestParam(required = false) String objectType,
                                      @RequestParam(required = false) String objectId,
                                      @RequestParam(required = false) String correlationId,
                                      @RequestParam(required = false) Instant from,
                                      @RequestParam(required = false) Instant to,
                                      @RequestParam(required = false) Integer limit,
                                      @RequestParam(required = false) String cursor) {
        return audit.search(actors.require(), new AuditSearch(actorIdentityId, action, objectType, objectId, correlationId, from, to),
                PageRequest.of(limit, cursor));
    }

    @GetMapping("/audit/verification")
    @RequiresPermission(Permissions.AUDIT_VERIFY)
    @RequiresStepUp
    AuditVerificationResult verify() {
        return audit.verify(actors.require());
    }
}
