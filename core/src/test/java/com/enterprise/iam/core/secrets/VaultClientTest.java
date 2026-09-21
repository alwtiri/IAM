package com.enterprise.iam.core.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.secrets.api.SecretRef;
import com.enterprise.iam.core.secrets.application.VaultClient;
import com.enterprise.iam.core.secrets.application.VaultSecretStore;
import com.enterprise.iam.kernel.ErrorCode;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Secret;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Exercises the Vault client against an in-process HTTP stub (JDK HttpServer) — G3 fail-closed behaviour. */
class VaultClientTest {

    private static VaultClient client(URI uri) {
        return new VaultClient(uri, "iam", () -> new VaultClient.AppRoleCredentials("role-1", Secret.of("sid-1")),
                VaultClient.defaultHttpClient(Duration.ofMillis(500)), Duration.ofSeconds(2), Clock.systemUTC());
    }

    @Test
    void logsInWritesAndDestroysWithoutLeakingSecretsIntoErrors() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> bodies = new ArrayList<>();
        AtomicInteger logins = new AtomicInteger();
        server.createContext("/v1/auth/approle/login", ex -> {
            logins.incrementAndGet();
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "{\"auth\":{\"client_token\":\"hvs.test\",\"lease_duration\":3600}}");
        });
        server.createContext("/v1/iam/data/providers/p1/connection", ex -> {
            assertEquals("hvs.test", ex.getRequestHeaders().getFirst("X-Vault-Token"));
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "{\"data\":{\"version\":3}}");
        });
        server.createContext("/v1/iam/metadata/providers/p1/connection", ex -> respond(ex, 204, ""));
        server.createContext("/v1/sys/health", ex -> respond(ex, 503, "{}"));
        server.start();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            VaultSecretStore store = new VaultSecretStore(client(uri));
            SecretRef ref = store.write("providers/p1/connection", Secret.of("s3cr3t"));
            assertEquals("vault:iam/providers/p1/connection#3", ref.value());
            store.write("providers/p1/connection", Secret.of("again"));
            assertEquals(1, logins.get(), "token is reused until close to expiry");
            store.destroy(ref);
            assertTrue(bodies.get(0).contains("\"role_id\":\"role-1\""));
            assertTrue(bodies.get(1).contains("s3cr3t"), "value is sent to Vault");
            assertEquals(VaultClient.HealthState.SEALED, client(uri).health());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unreachableVaultFailsClosedWithSecretsUnavailable() {
        VaultClient c = client(URI.create("http://127.0.0.1:1"));
        IamException e = assertThrows(IamException.class, () -> c.writeKv("providers/x/connection", Secret.of("v")));
        assertEquals(ErrorCode.SECRETS_UNAVAILABLE, e.code());
        assertFalse(e.getMessage().contains("v\""));
        assertEquals(VaultClient.HealthState.UNREACHABLE, c.health());
    }

    @Test
    void permissionErrorsAlsoFailClosed() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/auth/approle/login", ex -> respond(ex, 400, "{\"errors\":[\"invalid role or secret ID\"]}"));
        server.start();
        try {
            VaultClient c = client(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
            IamException e = assertThrows(IamException.class, () -> c.writeKv("providers/x/connection", Secret.of("v")));
            assertEquals(ErrorCode.SECRETS_UNAVAILABLE, e.code());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void pathTraversalIsRejected() {
        VaultClient c = client(URI.create("http://127.0.0.1:1"));
        assertThrows(IllegalArgumentException.class, () -> c.writeKv("../sys/policy", Secret.of("v")));
        assertThrows(IllegalArgumentException.class, () -> c.destroyKv("providers//x"));
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            ex.getResponseBody().write(b);
        }
        ex.close();
    }
}
