package com.enterprise.iam.provider.spi.model;

import com.enterprise.iam.provider.spi.CredentialHandle;
import java.util.Objects;

/**
 * Password change/reset/rotation request. Both values are handles; the provider redeems them just-in-time.
 *
 * @param account       the account
 * @param currentSecret handle to the current secret (required for CHANGE, optional for RESET/ROTATE)
 * @param newSecret     handle to the new secret (a PENDING Vault version during rotation, ADR-0005)
 */
public record PasswordChange(AccountRef account, CredentialHandle currentSecret, CredentialHandle newSecret) {
    public PasswordChange {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(newSecret, "newSecret");
    }
}
