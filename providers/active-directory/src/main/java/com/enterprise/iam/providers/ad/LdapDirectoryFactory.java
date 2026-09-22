package com.enterprise.iam.providers.ad;

import com.enterprise.iam.kernel.Secret;
import java.io.IOException;
import java.time.Duration;

/** Opens bound LDAP connections (production: {@link UnboundIdDirectoryFactory}; tests: in-memory fakes). */
@FunctionalInterface
public interface LdapDirectoryFactory {

    enum Security { LDAPS, START_TLS, PLAIN }

    /**
     * @param caCertificatesPem        PEM trust anchors for the domain controllers, or null for the JVM trust store
     * @param pinnedCertificateSha256  hex SHA-256 of the DC certificate (alternative to a CA), or null
     */
    record Config(String host, int port, Security security, String bindDn, String caCertificatesPem, String pinnedCertificateSha256,
                  Duration timeout) {
    }

    LdapDirectory open(Config config, Secret password) throws IOException;
}
