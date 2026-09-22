package com.enterprise.iam.providers.genericrest;

import com.enterprise.iam.provider.spi.Provider;
import com.enterprise.iam.provider.spi.ProviderConnection;
import com.enterprise.iam.provider.spi.ProviderDescriptor;
import com.enterprise.iam.provider.spi.ProviderFactory;
import java.net.http.HttpClient;
import java.time.Duration;

/** ServiceLoader entry point of the generic-rest (SCIM 2.0) provider. One shared HTTP client per worker. */
public final class ScimProviderFactory implements ProviderFactory {

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Override
    public ProviderDescriptor descriptor() {
        return ScimProvider.DESCRIPTOR;
    }

    @Override
    public Provider create(ProviderConnection connection) {
        if (!connection.endpoint().startsWith("https://") && !"true".equals(connection.settings().get("allowInsecureHttp"))) {
            throw new IllegalArgumentException("SCIM endpoint must use https:// (set allowInsecureHttp=true only for lab targets)");
        }
        return new ScimProvider(connection, HTTP);
    }
}
