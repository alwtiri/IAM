package com.enterprise.iam.core.shared.infrastructure.web;

import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.AuthenticatedEndpoint;
import com.enterprise.iam.core.shared.api.security.CurrentActor;
import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.core.shared.api.security.InternalEndpoint;
import com.enterprise.iam.core.shared.api.security.PublicEndpoint;
import com.enterprise.iam.core.shared.api.security.RequiresPermission;
import com.enterprise.iam.core.shared.api.security.RequiresStepUp;
import com.enterprise.iam.core.shared.api.security.StepUpPolicy;
import com.enterprise.iam.core.shared.api.security.WorkerPrincipal;
import com.enterprise.iam.kernel.IamException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Coarse, declarative endpoint check (SEC1): a handler without a security annotation is denied (fail closed),
 * {@link RequiresPermission} requires the permission in at least one scope, {@link RequiresStepUp} requires recent MFA.
 * Object-level checks happen in the application services.
 */
public class PermissionInterceptor implements HandlerInterceptor {

    private final CurrentActorProvider actors;
    private final AccessGuard guard;
    private final StepUpPolicy stepUp;
    private final Clock clock;

    public PermissionInterceptor(CurrentActorProvider actors, AccessGuard guard, StepUpPolicy stepUp, Clock clock) {
        this.actors = actors;
        this.guard = guard;
        this.stepUp = stepUp;
        this.clock = clock;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) {
            return true; // static resources / error dispatch: not API handlers
        }
        boolean worker = request.getAttribute(WorkerPrincipal.REQUEST_ATTRIBUTE) != null;
        if (hm.hasMethodAnnotation(InternalEndpoint.class)) {
            if (!worker) {
                throw IamException.notFound("Resource"); // internal handlers do not exist for anyone else
            }
            return true;
        }
        if (worker) {
            throw IamException.notFound("Resource"); // workers only reach /internal handlers
        }
        if (hm.hasMethodAnnotation(PublicEndpoint.class)) {
            return true;
        }
        RequiresPermission rp = hm.getMethodAnnotation(RequiresPermission.class);
        boolean authenticatedOnly = hm.hasMethodAnnotation(AuthenticatedEndpoint.class);
        if (rp == null && !authenticatedOnly) {
            throw IamException.accessDenied(); // undeclared handler: deny
        }
        CurrentActor actor = actors.require();
        if (rp != null && !guard.holdsAnywhere(actor, rp.value())) {
            throw IamException.accessDenied();
        }
        if (hm.hasMethodAnnotation(RequiresStepUp.class) && !stepUp.isSatisfied(actor, clock.instant())) {
            throw IamException.stepUpRequired();
        }
        return true;
    }
}
