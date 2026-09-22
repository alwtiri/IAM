package com.enterprise.iam.providers.linux;

import com.enterprise.iam.kernel.Secret;
import java.io.IOException;
import java.time.Duration;

/** Opens authenticated SSH sessions (production: {@link MinaSshTransportFactory}; tests: scripted fakes). */
@FunctionalInterface
public interface SshTransportFactory {

    /**
     * @param hostKeyFingerprint expected server key fingerprint ({@code SHA256:...}); null only when the instance explicitly
     *                           allows unknown host keys (lab)
     * @param keyAuth            true: {@code credential} is a private key (OpenSSH/PKCS#8 PEM); false: a password
     */
    SshTransport open(String host, int port, String username, Secret credential, boolean keyAuth, String hostKeyFingerprint,
                      Duration timeout) throws IOException;
}
