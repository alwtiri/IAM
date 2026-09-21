package com.enterprise.iam.core.shared.infrastructure.web;

import com.enterprise.iam.core.shared.api.security.AccessGuard;
import com.enterprise.iam.core.shared.api.security.CurrentActorProvider;
import com.enterprise.iam.core.shared.api.security.StepUpPolicy;
import java.time.Clock;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the declarative permission check for every public API handler. */
@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

    private final CurrentActorProvider actors;
    private final AccessGuard guard;
    private final StepUpPolicy stepUp;
    private final Clock clock;

    WebConfiguration(CurrentActorProvider actors, AccessGuard guard, StepUpPolicy stepUp, Clock clock) {
        this.actors = actors;
        this.guard = guard;
        this.stepUp = stepUp;
        this.clock = clock;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PermissionInterceptor(actors, guard, stepUp, clock)).addPathPatterns("/api/**");
    }
}
