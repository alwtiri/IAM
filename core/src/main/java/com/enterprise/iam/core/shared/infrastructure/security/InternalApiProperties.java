package com.enterprise.iam.core.shared.infrastructure.security;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Internal mTLS listener for the worker plane.
 *
 * @param enabled   start the listener (compose: true; unit tests: false)
 * @param port      listener port, reachable only on the core network
 * @param certFile  PEM server certificate of iam-core
 * @param keyFile   PEM private key (PKCS#8) of iam-core
 * @param caFile    PEM CA that signs worker client certificates
 * @param workers   certificate CN → provider types the worker serves
 */
@ConfigurationProperties(prefix = "iam.internal-api")
public record InternalApiProperties(boolean enabled, Integer port, String certFile, String keyFile, String caFile,
                                    Map<String, Set<String>> workers) {

    public InternalApiProperties {
        port = port == null ? 8443 : port;
        workers = workers == null ? Map.of() : Map.copyOf(workers);
    }
}
