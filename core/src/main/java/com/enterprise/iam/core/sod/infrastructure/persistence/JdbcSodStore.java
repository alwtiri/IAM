package com.enterprise.iam.core.sod.infrastructure.persistence;

import com.enterprise.iam.core.sod.application.SodStore;
import com.enterprise.iam.core.sod.domain.SodRule;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

public class JdbcSodStore implements SodStore {

    private final JdbcClient jdbc;

    public JdbcSodStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<SodRule> rules() {
        return jdbc.sql("SELECT id, code, name, left_role, right_role, mode, severity, enabled FROM sod.rule ORDER BY code")
                .query((rs, n) -> new SodRule(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("left_role"),
                        rs.getString("right_role"), rs.getString("mode"), rs.getString("severity"), rs.getBoolean("enabled"))).list();
    }
}
