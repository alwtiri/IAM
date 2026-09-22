package com.enterprise.iam.core.request.infrastructure.persistence;

import com.enterprise.iam.core.request.application.RequestStore;
import com.enterprise.iam.core.request.domain.AccessRequest;
import com.enterprise.iam.core.request.domain.ApprovalStep;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcRequestStore implements RequestStore {

    private static final String SELECT = "SELECT * FROM request.access_request";

    private final JdbcClient jdbc;

    public JdbcRequestStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(AccessRequest r, List<ApprovalStep> steps) {
        jdbc.sql("""
                INSERT INTO request.access_request (id, requester_id, beneficiary_id, type, role_id, role_code, scope_type, scope_value,
                    justification, duration_days, status, status_reason, decision, sod_conflicts, role_assignment_id, valid_until,
                    created_at, updated_at, version)
                VALUES (:id, :requester, :beneficiary, :type, :role, :roleCode, :scopeType, :scopeValue, :justification, :days, :status,
                    :reason, CAST(:decision AS jsonb), CAST(:sod AS jsonb), :assignment, :until, :created, :updated, 0)""")
                .param("id", r.id()).param("requester", r.requesterId()).param("beneficiary", r.beneficiaryId()).param("type", r.type())
                .param("role", r.roleId()).param("roleCode", r.roleCode()).param("scopeType", r.scopeType()).param("scopeValue", r.scopeValue())
                .param("justification", r.justification()).param("days", r.durationDays()).param("status", r.status().name())
                .param("reason", r.statusReason()).param("decision", r.decisionJson()).param("sod", r.sodConflictsJson())
                .param("assignment", r.roleAssignmentId()).param("until", ts(r.validUntil())).param("created", ts(r.createdAt()))
                .param("updated", ts(r.updatedAt())).update();
        for (ApprovalStep s : steps) {
            jdbc.sql("""
                    INSERT INTO request.approval_step (request_id, step_no, approver_type, approver_role, approver_identity_id, status, note)
                    VALUES (:r, :n, :type, :role, :identity, :status, :note)""")
                    .param("r", s.requestId()).param("n", s.stepNo()).param("type", s.approverType()).param("role", s.approverRole())
                    .param("identity", s.approverIdentityId()).param("status", s.status()).param("note", s.note()).update();
        }
    }

    @Override
    public Optional<AccessRequest> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", id).query(JdbcRequestStore::map).optional();
    }

    @Override
    public List<ApprovalStep> steps(UUID requestId) {
        return jdbc.sql("SELECT * FROM request.approval_step WHERE request_id = :r ORDER BY step_no").param("r", requestId)
                .query((rs, n) -> new ApprovalStep(rs.getObject("request_id", UUID.class), rs.getInt("step_no"), rs.getString("approver_type"),
                        rs.getString("approver_role"), rs.getObject("approver_identity_id", UUID.class), rs.getString("status"),
                        rs.getObject("decided_by", UUID.class), instant(rs.getTimestamp("decided_at")), rs.getString("comment"),
                        rs.getString("auth_context"), rs.getString("note"))).list();
    }

    @Override
    public boolean update(AccessRequest r, long expectedVersion) {
        return jdbc.sql("""
                UPDATE request.access_request SET status = :status, status_reason = :reason, role_assignment_id = :assignment,
                    valid_until = :until, updated_at = :updated, version = version + 1
                WHERE id = :id AND version = :v""")
                .param("status", r.status().name()).param("reason", r.statusReason()).param("assignment", r.roleAssignmentId())
                .param("until", ts(r.validUntil())).param("updated", ts(r.updatedAt())).param("id", r.id()).param("v", expectedVersion)
                .update() == 1;
    }

    @Override
    public void updateStep(ApprovalStep s) {
        jdbc.sql("""
                UPDATE request.approval_step SET status = :status, decided_by = :by, decided_at = :at, comment = :comment,
                    auth_context = :acr WHERE request_id = :r AND step_no = :n""")
                .param("status", s.status()).param("by", s.decidedBy()).param("at", ts(s.decidedAt())).param("comment", s.comment())
                .param("acr", s.authContext()).param("r", s.requestId()).param("n", s.stepNo()).update();
    }

    @Override
    public boolean hasOpenRequest(UUID beneficiaryId, UUID roleId) {
        return jdbc.sql("SELECT count(*) FROM request.access_request WHERE beneficiary_id = :b AND role_id = :r AND status IN ('PENDING_APPROVAL','APPROVED')")
                .param("b", beneficiaryId).param("r", roleId).query(Long.class).single() > 0;
    }

    @Override
    public List<AccessRequest> byRequester(UUID requesterId, int limit) {
        return jdbc.sql(SELECT + " WHERE requester_id = :r ORDER BY created_at DESC LIMIT :l").param("r", requesterId).param("l", limit)
                .query(JdbcRequestStore::map).list();
    }

    @Override
    public List<AccessRequest> all(String status, int limit) {
        return status == null || status.isBlank()
                ? jdbc.sql(SELECT + " ORDER BY created_at DESC LIMIT :l").param("l", limit).query(JdbcRequestStore::map).list()
                : jdbc.sql(SELECT + " WHERE status = :s ORDER BY created_at DESC LIMIT :l").param("s", status).param("l", limit)
                        .query(JdbcRequestStore::map).list();
    }

    @Override
    public List<AccessRequest> pendingFor(UUID identityId, List<String> roleCodes, int limit) {
        // IN (:codes) with a never-matching element keeps the SQL valid for identities without roles
        List<String> codes = new java.util.ArrayList<>(roleCodes);
        codes.add("");
        return jdbc.sql("""
                SELECT r.* FROM request.access_request r
                JOIN request.approval_step s ON s.request_id = r.id AND s.status = 'PENDING'
                WHERE r.status = 'PENDING_APPROVAL' AND r.requester_id <> :me AND r.beneficiary_id <> :me
                  AND ((s.approver_type = 'MANAGER' AND s.approver_identity_id = :me) OR (s.approver_type = 'ROLE' AND s.approver_role IN (:codes)))
                ORDER BY r.created_at LIMIT :l""")
                .param("me", identityId).param("codes", codes).param("l", limit).query(JdbcRequestStore::map).list();
    }

    @Override
    public Optional<AccessRequest> byAssignment(UUID roleAssignmentId) {
        return jdbc.sql(SELECT + " WHERE role_assignment_id = :a").param("a", roleAssignmentId).query(JdbcRequestStore::map).optional();
    }

    private static AccessRequest map(ResultSet rs, int n) throws SQLException {
        return new AccessRequest(rs.getObject("id", UUID.class), rs.getObject("requester_id", UUID.class), rs.getObject("beneficiary_id", UUID.class),
                rs.getString("type"), rs.getObject("role_id", UUID.class), rs.getString("role_code"), rs.getString("scope_type"),
                rs.getString("scope_value"), rs.getString("justification"), rs.getInt("duration_days"),
                AccessRequest.Status.valueOf(rs.getString("status")), rs.getString("status_reason"), rs.getString("decision"),
                rs.getString("sod_conflicts"), rs.getObject("role_assignment_id", UUID.class), instant(rs.getTimestamp("valid_until")),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")), rs.getLong("version"));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
