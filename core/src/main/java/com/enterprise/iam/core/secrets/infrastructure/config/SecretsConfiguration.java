package com.enterprise.iam.core.secrets.infrastructure.config;

import com.enterprise.iam.core.secrets.application.VaultClient;
import com.enterprise.iam.core.secrets.application.VaultHealthCheck;
import com.enterprise.iam.core.secrets.application.VaultSecretStore;
import com.enterprise.iam.kernel.Secret;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Vault wiring. AppRole role/secret ids are read from mounted files at login time (never from environment variables
 * or configuration values), so rotating the secret id only requires replacing the file.
 */
@Configuration(proxyBeanMethods = false)
class SecretsConfiguration {

    @Bean
    VaultClient vaultClient(@Value("${iam.vault.address:http://vault:8200}") URI address,
                            @Value("${iam.vault.kv-mount:iam}") String mount,
                            @Value("${iam.vault.role-id-file:/run/secrets/vault_role_id}") Path roleIdFile,
                            @Value("${iam.vault.secret-id-file:/run/secrets/vault_secret_id}") Path secretIdFile,
                            Clock clock) {
        return new VaultClient(address, mount, () -> new VaultClient.AppRoleCredentials(read(roleIdFile), Secret.of(read(secretIdFile))),
                VaultClient.defaultHttpClient(Duration.ofSeconds(2)), Duration.ofSeconds(5), clock);
    }

    @Bean
    VaultSecretStore secretStore(VaultClient vault) {
        return new VaultSecretStore(vault);
    }

    @Bean
    VaultHealthCheck vaultHealthCheck(VaultClient vault) {
        return new VaultHealthCheck(vault);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Vault credential file not readable: " + file.getFileName(), e);
        }
    }
}
