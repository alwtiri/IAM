package com.enterprise.iam.core.account.infrastructure.persistence;

import com.enterprise.iam.core.account.application.CredentialVaultStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcCredentialVaultStore implements CredentialVaultStore {

    private static final String VAULTED = """
            SELECT account_id, secret_path, credential_secret_ref, pending_secret_ref, rotation_status, rotation_operation_id, rotation_trigger,
                   last_rotation_error, last_rotated_at, rotation_interval_days, managed_by, created_at, version
            FROM account.managed_account""";
    private static final String CHECKOUT = "SELECT * FROM account.credential_checkout";

    private final JdbcClient jdbc;

    public JdbcCredentialVaultStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Vaulted> find(UUID accountId) {
        return jdbc.sql(VAULTED + " WHERE account_id = :a").param("a", accountId).query(JdbcCredentialVaultStore::vaulted).optional();
    }

    @Override
    public Optional<Vaulted> lock(UUID accountId) {
        return jdbc.sql(VAULTED + " WHERE account_id = :a FOR UPDATE").param("a", accountId).query(JdbcCredentialVaultStore::vaulted).optional();
    }

    @Override
    public Optional<Vaulted> findByOperation(UUID operationId) {
        return jdbc.sql(VAULTED + " WHERE rotation_operation_id = :o FOR UPDATE").param("o", operationId)
                .query(JdbcCredentialVaultStore::vaulted).optional();
    }

    @Override
    public List<Vaulted> all() {
        return jdbc.sql(VAULTED + " WHERE secret_path IS NOT NULL ORDER BY created_at").query(JdbcCredentialVaultStore::vaulted).list();
    }

    @Override
    public void insert(Vaulted v, Instant now) {
        jdbc.sql("""
                INSERT INTO account.managed_account (account_id, secret_path, credential_secret_ref, pending_secret_ref, rotation_status,
                    rotation_operation_id, rotation_trigger, last_rotation_error, last_rotated_at, rotation_interval_days, managed_by,
                    created_at, updated_at, version)
                VALUES (:a, :path, :cur, :pending, :status, :op, :trigger, :error, :rotated, :interval, :by, :now, :now, 0)
                ON CONFLICT (account_id) DO UPDATE SET secret_path = EXCLUDED.secret_path, pending_secret_ref = EXCLUDED.pending_secret_ref,
                    rotation_status = EXCLUDED.rotation_status, rotation_operation_id = EXCLUDED.rotation_operation_id,
                    rotation_trigger = EXCLUDED.rotation_trigger, managed_by = EXCLUDED.managed_by, updated_at = EXCLUDED.updated_at,
                    version = account.managed_account.version + 1""")
                .param("a", v.accountId()).param("path", v.secretPath()).param("cur", v.currentRef()).param("pending", v.pendingRef())
                .param("status", v.rotationStatus()).param("op", v.rotationOperationId()).param("trigger", v.rotationTrigger())
                .param("error", v.lastError()).param("rotated", ts(v.lastRotatedAt())).param("interval", v.rotationIntervalDays())
                .param("by", v.managedBy()).param("now", ts(now)).update();
    }

    @Override
    public boolean update(Vaulted v, Instant now) {
        return jdbc.sql("""
                UPDATE account.managed_account SET secret_path = :path, credential_secret_ref = :cur, pending_secret_ref = :pending,
                    rotation_status = :status, rotation_operation_id = :op, rotation_trigger = :trigger, last_rotation_error = :error,
                    last_rotated_at = :rotated, rotation_interval_days = :interval, managed_by = :by, updated_at = :now, version = version + 1
                WHERE account_id = :a AND version = :v""")
                .param("path", v.secretPath()).param("cur", v.currentRef()).param("pending", v.pendingRef()).param("status", v.rotationStatus())
                .param("op", v.rotationOperationId()).param("trigger", v.rotationTrigger()).param("error", v.lastError())
                .param("rotated", ts(v.lastRotatedAt())).param("interval", v.rotationIntervalDays()).param("by", v.managedBy())
                .param("now", ts(now)).param("a", v.accountId()).param("v", v.version()).update() == 1;
    }

    @Override
    public void insertCheckout(Checkout c) {
        jdbc.sql("""
                INSERT INTO account.credential_checkout (id, account_id, identity_id, request_id, reason, started_at, not_after, status)
                VALUES (:id, :a, :i, :r, :reason, :start, :end, 'ACTIVE')""")
                .param("id", c.id()).param("a", c.accountId()).param("i", c.identityId()).param("r", c.requestId()).param("reason", c.reason())
                .param("start", ts(c.startedAt())).param("end", ts(c.notAfter())).update();
    }

    @Override
    public Optional<Checkout> findCheckout(UUID id) {
        return jdbc.sql(CHECKOUT + " WHERE id = :id").param("id", id).query(JdbcCredentialVaultStore::checkout).optional();
    }

    @Override
    public Optional<Checkout> activeCheckout(UUID accountId) {
        return jdbc.sql(CHECKOUT + " WHERE account_id = :a AND status = 'ACTIVE'").param("a", accountId).query(JdbcCredentialVaultStore::checkout)
                .optional();
    }

    @Override
    public boolean endCheckout(UUID id, String status, UUID endedBy, Instant at) {
        return jdbc.sql("UPDATE account.credential_checkout SET status = :s, ended_by = :by, ended_at = :at WHERE id = :id AND status = 'ACTIVE'")
                .param("s", status).param("by", endedBy).param("at", ts(at)).param("id", id).update() == 1;
    }

    @Override
    public void recordReveal(UUID id, Instant at) {
        jdbc.sql("UPDATE account.credential_checkout SET reveal_count = reveal_count + 1, last_revealed_at = :at WHERE id = :id")
                .param("at", ts(at)).param("id", id).update();
    }

    @Override
    public List<Checkout> checkoutsOf(UUID identityId, int limit) {
        return jdbc.sql(CHECKOUT + " WHERE identity_id = :i ORDER BY started_at DESC LIMIT :l").param("i", identityId).param("l", limit)
                .query(JdbcCredentialVaultStore::checkout).list();
    }

    @Override
    public List<Checkout> recentCheckouts(int limit) {
        return jdbc.sql(CHECKOUT + " ORDER BY started_at DESC LIMIT :l").param("l", limit).query(JdbcCredentialVaultStore::checkout).list();
    }

    @Override
    public List<Checkout> overdue(Instant now) {
        return jdbc.sql(CHECKOUT + " WHERE status = 'ACTIVE' AND not_after <= :now ORDER BY not_after LIMIT 100").param("now", ts(now))
                .query(JdbcCredentialVaultStore::checkout).list();
    }

    private static Vaulted vaulted(ResultSet rs, int n) throws SQLException {
        int interval = rs.getInt("rotation_interval_days");
        Integer intervalDays = rs.wasNull() ? null : interval;
        return new Vaulted(rs.getObject("account_id", UUID.class), rs.getString("secret_path"), rs.getString("credential_secret_ref"),
                rs.getString("pending_secret_ref"), rs.getString("rotation_status"), rs.getObject("rotation_operation_id", UUID.class),
                rs.getString("rotation_trigger"), rs.getString("last_rotation_error"), instant(rs.getTimestamp("last_rotated_at")), intervalDays,
                rs.getObject("managed_by", UUID.class), instant(rs.getTimestamp("created_at")), rs.getLong("version"));
    }

    private static Checkout checkout(ResultSet rs, int n) throws SQLException {
        return new Checkout(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class), rs.getObject("identity_id", UUID.class),
                rs.getObject("request_id", UUID.class), rs.getString("reason"), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("not_after")), rs.getString("status"), instant(rs.getTimestamp("ended_at")),
                rs.getObject("ended_by", UUID.class), rs.getInt("reveal_count"), instant(rs.getTimestamp("last_revealed_at")));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
