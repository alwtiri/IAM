package com.enterprise.iam.core.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.shared.api.security.AuthenticatedEndpoint;
import com.enterprise.iam.core.shared.api.security.PublicEndpoint;
import com.enterprise.iam.core.shared.api.security.RequiresPermission;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** SEC1: every API handler declares its security requirement; the build fails otherwise. */
class EndpointSecurityCoverageTest {

    @Test
    void everyHandlerDeclaresSecurity() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> missing = new ArrayList<>();
        int handlers = 0;
        for (BeanDefinition bd : scanner.findCandidateComponents("com.enterprise.iam.core")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            for (Method m : type.getDeclaredMethods()) {
                if (!AnnotatedElementUtils.hasAnnotation(m, RequestMapping.class)) {
                    continue;
                }
                handlers++;
                boolean declared = m.isAnnotationPresent(RequiresPermission.class) || m.isAnnotationPresent(AuthenticatedEndpoint.class)
                        || m.isAnnotationPresent(PublicEndpoint.class);
                if (!declared) {
                    missing.add(type.getSimpleName() + "#" + m.getName());
                }
            }
        }
        assertTrue(handlers >= 35, "expected the Phase 2 API surface, found " + handlers + " handlers");
        assertTrue(missing.isEmpty(), "Handlers without security declaration: " + missing);
    }
}
