package com.enterprise.iam.core.shared.api.jdbc;

import com.enterprise.iam.kernel.Json;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** JDBC conversions shared by module repositories (PostgreSQL: timestamptz ↔ OffsetDateTime, jsonb ↔ text). */
public final class JdbcTypes {

    private JdbcTypes() {
    }

    public static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    public static LocalDate date(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDate.class);
    }

    public static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    /** Serializes a string map for a {@code CAST(:x AS jsonb)} parameter. */
    public static String json(Map<String, ?> map) {
        return Json.write(map == null ? Map.of() : map);
    }

    /** Reads a jsonb column selected as text into a string map (non-string values are stringified). */
    public static Map<String, String> stringMap(ResultSet rs, String column) throws SQLException {
        String text = rs.getString(column);
        Map<String, String> out = new LinkedHashMap<>();
        if (text != null && !text.isBlank()) {
            Json.parseObject(text).forEach((k, v) -> out.put(k, v == null ? null : v.toString()));
        }
        return out;
    }
}
