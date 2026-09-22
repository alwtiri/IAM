package com.enterprise.iam.providers.linux;

import com.enterprise.iam.provider.spi.Provider;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.ProviderDescriptor;
import com.enterprise.iam.provider.spi.ProviderFactory;

/** ServiceLoader entry point of the linux-ssh provider. The MINA SSH client is created lazily on first use. */
public final class LinuxProviderFactory implements ProviderFactory {

    private final SshTransportFactory transports;

    public LinuxProviderFactory() {
        this(new LazyMina());
    }

    LinuxProviderFactory(SshTransportFactory transports) {
        this.transports = transports;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return LinuxProvider.DESCRIPTOR;
    }

    @Override
    public Provider create(ProviderConnection connection) {
        return new LinuxProvider(connection, transports);
    }

    /** Defers loading MINA until an SSH connection is actually needed (contract tests never connect). */
    private static final class LazyMina implements SshTransportFactory {
        private volatile SshTransportFactory delegate;

        @Override
        public SshTransport open(String host, int port, String username, com.enterprise.iam.kernel.Secret credential, boolean keyAuth,
                                 String hostKeyFingerprint, java.time.Duration timeout) throws java.io.IOException {
            SshTransportFactory d = delegate;
            if (d == null) {
                synchronized (this) {
                    if (delegate == null) {
                        delegate = new MinaSshTransportFactory();
                    }
                    d = delegate;
                }
            }
            return d.open(host, port, username, credential, keyAuth, hostKeyFingerprint, timeout);
        }
    }
}
