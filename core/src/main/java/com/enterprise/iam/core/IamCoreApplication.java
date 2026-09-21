package com.enterprise.iam.core;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * iam-core — Enterprise IAM / PAM governance Core (modular monolith, ADR-0002).
 *
 * <p>Every direct sub-package is an application module with explicitly allowed dependencies
 * (see each module's {@code package-info.java} and docs/architecture/MODULE-BOUNDARIES.md).
 * Phase 1 contains the module skeleton only; business functionality starts in Phase 2.
 */
@SpringBootApplication
public class IamCoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(IamCoreApplication.class, args);
    }
}
