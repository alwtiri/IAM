package com.enterprise.iam.core.identity.infrastructure.keycloak;

import com.enterprise.iam.core.identity.application.LoginAccountProvisioner;
import com.enterprise.iam.kernel.IamException;
import com.enterprise.iam.kernel.Json;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keycloak Admin REST adapter. Authenticates as the dedicated confidential client {@code iam-core-admin} (client
 * credentials; its service account holds only realm-management manage-users/view-users/query-users). The platform
 * never sets a password: new users get required actions UPDATE_PASSWORD and CONFIGURE_TOTP and an invitation e-mail.
 */
public final class KeycloakLoginProvisioner implements LoginAccountProvisioner {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(KeycloakLoginProvisioner.class);

    static final List<String> REQUIRED_ACTIONS = List.of("UPDATE_PASSWORD", "CONFIGURE_TOTP");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** Invitation link lifetime: 72 hours. */
    private static final int INVITATION_LIFESPAN_SECONDS = 72 * 3600;

    private final String baseUrl;
    private final String realm;
    private final String clientId;
    private final String clientSecret;
    private final String loginClientId;
    private final String redirectUri;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();

    public KeycloakLoginProvisioner(String baseUrl, String realm, String clientId, String clientSecret, String loginClientId, String redirectUri) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret == null ? "" : clientSecret.strip();
        this.loginClientId = loginClientId;
        this.redirectUri = redirectUri;
    }

    @Override
    public boolean enabled() {
        return !clientSecret.isEmpty();
    }

    @Override
    public String createOrFind(NewLogin login) {
        String token = token();
        Map<String, Object> user = Map.of("username", login.username(), "email", login.email(), "firstName", login.givenName(),
                "lastName", login.familyName(), "enabled", true, "emailVerified", false, "requiredActions", REQUIRED_ACTIONS);
        HttpResponse<String> r = send(HttpRequest.newBuilder(admin("/users")).header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(user))));
        if (r.statusCode() == 201) {
            String location = r.headers().firstValue("Location").orElseThrow(() -> failure("user created without Location header"));
            return location.substring(location.lastIndexOf('/') + 1);
        }
        if (r.statusCode() == 409) {
            // Existing Keycloak user: link only when it is the same person (same e-mail), never take over another account.
            HttpResponse<String> found = send(HttpRequest.newBuilder(admin("/users?exact=true&username=" + enc(login.username())))
                    .header("Authorization", "Bearer " + token).GET());
            if (found.statusCode() == 200 && Json.parse(found.body()) instanceof List<?> list && !list.isEmpty()
                    && list.get(0) instanceof Map<?, ?> existing) {
                Object email = existing.get("email");
                if (email != null && email.toString().equalsIgnoreCase(login.email())) {
                    return String.valueOf(existing.get("id"));
                }
            }
            throw IamException.validation("username", "LOGIN_EXISTS",
                    "a login with this username or e-mail already exists in the identity provider for a different person");
        }
        throw failure("create user answered HTTP " + r.statusCode());
    }

    @Override
    public void sendInvitation(String subject) {
        String q = "?lifespan=" + INVITATION_LIFESPAN_SECONDS + "&client_id=" + enc(loginClientId) + "&redirect_uri=" + enc(redirectUri);
        HttpResponse<String> r = send(HttpRequest.newBuilder(admin("/users/" + enc(subject) + "/execute-actions-email" + q))
                .header("Authorization", "Bearer " + token()).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(Json.write(REQUIRED_ACTIONS))));
        if (r.statusCode() != 204) {
            throw failure("invitation e-mail answered HTTP " + r.statusCode() + " (is SMTP configured for the realm?)");
        }
    }

    @Override
    public void setEnabled(String subject, boolean enabled) {
        HttpResponse<String> r = send(HttpRequest.newBuilder(admin("/users/" + enc(subject))).header("Authorization", "Bearer " + token())
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(Json.write(Map.of("enabled", enabled)))));
        if (r.statusCode() != 204 && r.statusCode() != 404) {
            throw failure("update user answered HTTP " + r.statusCode());
        }
    }

    private String token() {
        String form = "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret);
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create(baseUrl + "/realms/" + enc(realm) + "/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)));
        if (r.statusCode() != 200) {
            throw failure("admin client authentication answered HTTP " + r.statusCode());
        }
        Object token = Json.parseObject(r.body()).get("access_token");
        if (token == null) {
            throw failure("no access token in the token response");
        }
        return token.toString();
    }

    private URI admin(String path) {
        return URI.create(baseUrl + "/admin/realms/" + enc(realm) + path);
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return http.send(request.timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw IamException.dependencyUnavailable("keycloak", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw IamException.dependencyUnavailable("keycloak", e);
        }
    }

    private static IamException failure(String message) {
        LOG.warn("Keycloak admin API: {}", message);
        return IamException.dependencyUnavailable("keycloak", new IllegalStateException(message.toLowerCase(Locale.ROOT)));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
