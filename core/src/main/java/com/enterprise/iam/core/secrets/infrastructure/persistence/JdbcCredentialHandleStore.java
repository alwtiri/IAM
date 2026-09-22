package com.enterprise.iam.core.secrets.infrastructure.persistence;

import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.instant;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.ts;
import static com.enterprise.iam.core.shared.api.jdbc.JdbcTypes.uuid;

import com.enterprise.iam.core.secrets.application.CredentialHandleStore;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcCredentialHandleStore implements CredentialHandleStore {

    private final JdbcClient jdbc;

    public JdbcCredentialHandleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(byte[] handleHash, UUID operationId, String purpose, String secretRef, String providerType, Instant issuedAt,
                       Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO secrets.credential_handle (handle_hash, operation_id, purpose, secret_ref, provider_type, issued_at, expires_at)
                VALUES (:h, :op, :purpose, :ref, :ptype, :issued, :expires)""")
                .param("h", handleHash).param("op", operationId).param("purpose", purpose).param("ref", secretRef)
                .param("ptype", providerType).param("issued", ts(issuedAt)).param("expires", ts(expiresAt)).update();
    }

    @Override
    public Optional<Row> lock(byte[] handleHash) {
        return jdbc.sql("SELECT * FROM secrets.credential_handle WHERE handle_hash = :h FOR UPDATE").param("h", handleHash)
                .query((rs, n) -> new Row(uuid(rs, "operation_id"), rs.getString("purpose"), rs.getString("secret_ref"),
                        rs.getString("provider_type"), instant(rs, "expires_at"), instant(rs, "redeemed_at")))
                .optional();
    }

    @Override
    public void markRedeemed(byte[] handleHash, String redeemedBy, Instant at) {
        jdbc.sql("UPDATE secrets.credential_handle SET redeemed_at = :at, redeemed_by = :by WHERE handle_hash = :h AND redeemed_at IS NULL")
                .param("at", ts(at)).param("by", redeemedBy).param("h", handleHash).update();
    }
}
