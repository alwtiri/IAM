package com.enterprise.iam.providers.ad;

import com.enterprise.iam.provider.spi.Provider;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.ProviderDescriptor;
import com.enterprise.iam.provider.spi.ProviderFactory;
import java.time.Clock;

/** ServiceLoader entry point of the active-directory provider. The UnboundID client is loaded lazily on first use. */
public final class ActiveDirectoryProviderFactory implements ProviderFactory {

    private final LdapDirectoryFactory directories;
    private final Clock clock;

    public ActiveDirectoryProviderFactory() {
        this((config, password) -> Holder.UNBOUNDID.open(config, password), Clock.systemUTC());
    }

    ActiveDirectoryProviderFactory(LdapDirectoryFactory directories, Clock clock) {
        this.directories = directories;
        this.clock = clock;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return ActiveDirectoryProvider.DESCRIPTOR;
    }

    @Override
    public Provider create(ProviderConnection connection) {
        return new ActiveDirectoryProvider(connection, directories, clock);
    }

    /** Initialization-on-demand holder: contract tests never connect, so they never load the LDAP SDK. */
    private static final class Holder {
        static final LdapDirectoryFactory UNBOUNDID = new UnboundIdDirectoryFactory();
    }
}
