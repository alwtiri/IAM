package com.enterprise.iam.providers.postgresql;

import com.enterprise.iam.kernel.Secret;
import java.io.IOException;
import java.time.Duration;

/** Opens database sessions. */
@FunctionalInterface
public interface DbSessions {

    record Target(String host, int port, String database, String username, String sslMode, String caCertificatePem, Duration timeout) {
    }

    DbSession open(Target target, Secret password) throws IOException;
}
