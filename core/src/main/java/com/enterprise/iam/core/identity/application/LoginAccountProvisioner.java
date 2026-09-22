package com.enterprise.iam.core.identity.application;

/**
 * Creates login accounts in the identity provider (Keycloak) for platform users. The platform never sets or sees the
 * user's password: the user receives an invitation to set it and to enrol MFA.
 */
public interface LoginAccountProvisioner {

    record NewLogin(String username, String email, String givenName, String familyName) {
    }

    /** False when the identity provider admin API is not configured (the UI then offers manual linking only). */
    boolean enabled();

    /** Creates the login account, or returns the existing one with the same username; returns its subject (user id). */
    String createOrFind(NewLogin login);

    /** Sends the invitation e-mail (set password, configure one-time password). */
    void sendInvitation(String subject);

    /** Blocks or unblocks sign-in for the account (identity suspended, disabled or reinstated). */
    void setEnabled(String subject, boolean enabled);
}
