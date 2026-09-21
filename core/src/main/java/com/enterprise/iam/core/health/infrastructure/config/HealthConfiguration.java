package com.enterprise.iam.core.health.infrastructure.config;

import com.enterprise.iam.core.health.application.HealthAggregator;
import com.enterprise.iam.core.shared.api.health.NetworkProbes;
import com.enterprise.iam.core.health.application.SystemService;
import com.enterprise.iam.core.shared.api.health.ComponentHealth;
import com.enterprise.iam.core.shared.api.health.ComponentHealthCheck;
import com.enterprise.iam.core.shared.api.security.AccessGuard;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

@Configuration(proxyBeanMethods = false)
class HealthConfiguration {

    @Bean
    ComponentHealthCheck databaseHealthCheck(JdbcClient jdbc) {
        return new ComponentHealthCheck() {
            @Override
            public String component() {
                return "postgresql";
            }

            @Override
            public ComponentHealth.Category category() {
                return ComponentHealth.Category.DATABASE;
            }

            @Override
            public ComponentHealth.Classification classification() {
                return ComponentHealth.Classification.CORE_DEPENDENCY;
            }

            @Override
            public List<String> affectedFunctionality() {
                return List.of("All platform functions (system of record)");
            }

            @Override
            public Result check() {
                Integer one = jdbc.sql("SELECT 1").query(Integer.class).single();
                return one != null && one == 1 ? Result.healthy() : Result.unavailable("unexpected result");
            }
        };
    }

    @Bean
    ComponentHealthCheck keycloakHealthCheck(@Value("${iam.auth.jwk-set-uri}") URI jwks) {
        return NetworkProbes.http("keycloak", ComponentHealth.Category.AUTHENTICATION, ComponentHealth.Classification.CORE_DEPENDENCY, jwks,
                List.of("New logins and step-up authentication fail closed; existing sessions continue until they expire"));
    }

    @Bean
    ComponentHealthCheck cacheHealthCheck(@Value("${iam.cache.host:cache}") String host, @Value("${iam.cache.port:6379}") int port,
                                          @Value("${iam.cache.password-file:/run/secrets/cache_password}") Path passwordFile) {
        return NetworkProbes.redis(host, port, () -> {
            try {
                return Files.exists(passwordFile) ? Files.readString(passwordFile, StandardCharsets.UTF_8).strip() : null;
            } catch (IOException e) {
                return null;
            }
        }, List.of("None in Phase 2 (the Core does not depend on the cache); later: slower authorization lookups"));
    }

    @Bean(destroyMethod = "shutdown")
    ExecutorService healthExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    HealthAggregator healthAggregator(List<ComponentHealthCheck> checks, ExecutorService healthExecutor, Clock clock) {
        return new HealthAggregator(checks, healthExecutor, clock, Duration.ofSeconds(5));
    }

    @Bean
    SystemService systemService(HealthAggregator health, AccessGuard guard, @Value("${iam.version:0.2.0-SNAPSHOT}") String version,
                                @Value("${iam.build-sha:dev}") String sha) {
        return new SystemService(health, guard, version, sha, List.of());
    }
}
