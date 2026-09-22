package com.enterprise.iam.worker.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param instance        name reported in results (defaults to the host name)
 * @param pools           provider types this worker serves; one queue {@code ops.<type>} each (G4)
 * @param concurrency     consumers per pool (type → count), default {@code defaultConcurrency}
 * @param bulkhead        concurrent calls per provider instance
 * @param coreInternalUrl base URL of the Core's internal mTLS listener
 * @param certFile        worker client certificate (PEM); {@code keyFile} its PKCS#8 key; {@code caFile} the internal CA
 * @param retryBackoff    base back-off between in-execution retries of transient failures
 */
@ConfigurationProperties(prefix = "iam.worker")
public record WorkerProperties(String instance, List<String> pools, Map<String, Integer> concurrency, Integer defaultConcurrency,
                               Integer bulkhead, URI coreInternalUrl, String certFile, String keyFile, String caFile,
                               Duration retryBackoff) {

    public WorkerProperties {
        pools = pools == null ? List.of() : List.copyOf(pools);
        concurrency = concurrency == null ? Map.of() : Map.copyOf(concurrency);
        defaultConcurrency = defaultConcurrency == null ? 4 : defaultConcurrency;
        bulkhead = bulkhead == null ? 4 : bulkhead;
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(2) : retryBackoff;
    }

    public int concurrencyOf(String type) {
        return concurrency.getOrDefault(type, defaultConcurrency);
    }
}
