package com.enterprise.iam.worker.runtime;

import com.enterprise.iam.kernel.Json;
import com.enterprise.iam.kernel.Secret;
import com.enterprise.iam.provider.spi.CredentialResolver.SecretsUnavailableException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLContext;

/**
 * Redeems credential handles at {@code POST /internal/v1/credential-handles:redeem} over mTLS. Response bodies are never
 * logged; errors carry only the HTTP status.
 */
public final class MtlsHandleRedeemer implements HandleRedeemer {

    private final HttpClient http;
    private final URI endpoint;

    public MtlsHandleRedeemer(URI coreInternalBase, SSLContext tls) {
        this.http = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.endpoint = coreInternalBase.resolve("/internal/v1/credential-handles:redeem");
    }

    @Override
    public Secret redeem(String handle) throws SecretsUnavailableException {
        HttpRequest req = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("handle", handle)), StandardCharsets.UTF_8)).build();
        HttpResponse<String> r;
        try {
            r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new SecretsUnavailableException("credential service unreachable: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SecretsUnavailableException("interrupted while redeeming a credential handle");
        }
        if (r.statusCode() != 200) {
            throw new SecretsUnavailableException(r.statusCode() == 503 ? "Vault unavailable (SECRETS_UNAVAILABLE)"
                    : "credential handle rejected (HTTP " + r.statusCode() + ")");
        }
        Object value = Json.parseObject(r.body()).get("value");
        if (!(value instanceof String v)) {
            throw new SecretsUnavailableException("malformed credential response");
        }
        return Secret.of(v);
    }
}
