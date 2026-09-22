package com.enterprise.iam.providers.windows;

import com.enterprise.iam.provider.spi.Provider;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.ProviderDescriptor;
import com.enterprise.iam.provider.spi.ProviderFactory;
import java.time.Clock;

/** ServiceLoader entry point of the windows-winrm provider. */
public final class WindowsProviderFactory implements ProviderFactory {

    private final WinRmTransport transport;
    private final Clock clock;

    public WindowsProviderFactory() {
        this(new HttpWinRmTransport(), Clock.systemUTC());
    }

    WindowsProviderFactory(WinRmTransport transport, Clock clock) {
        this.transport = transport;
        this.clock = clock;
    }

    @Override
    public ProviderDescriptor descriptor() {
        return WindowsProvider.DESCRIPTOR;
    }

    @Override
    public Provider create(ProviderConnection connection) {
        return new WindowsProvider(connection, transport, clock);
    }
}
