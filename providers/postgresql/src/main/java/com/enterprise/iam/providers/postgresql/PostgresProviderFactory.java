package com.enterprise.iam.providers.postgresql;

import com.enterprise.iam.provider.spi.Provider;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.ProviderDescriptor;
import com.enterprise.iam.provider.spi.ProviderFactory;
import java.time.Clock;

/** ServiceLoader entry point of the postgresql provider. */
public final class PostgresProviderFactory implements ProviderFactory {

    private final DbSessions sessions;
    private final Clock clock;

    public PostgresProviderFactory() {
        this(new JdbcDbSessions(), Clock.systemUTC());
    }

    PostgresProviderFactory(DbSessions sessions, Clock clock) {
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return PostgresProvider.DESCRIPTOR;
    }

    @Override
    public Provider create(ProviderConnection connection) {
        return new PostgresProvider(connection, sessions, clock);
    }
}
