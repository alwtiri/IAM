package com.enterprise.iam.providers.postgresql;

import com.enterprise.iam.kernel.Secret;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * JDBC sessions to PostgreSQL. TLS is verified by default ({@code sslmode=verify-full}); a CA supplied in the settings
 * is written to a private temporary file for the driver and deleted when the session closes. The password is passed
 * in memory only.
 */
final class JdbcDbSessions implements DbSessions {

    @Override
    public DbSession open(Target t, Secret password) throws IOException {
        Path ca = null;
        Properties props = new Properties();
        props.setProperty("user", t.username());
        props.setProperty("sslmode", t.sslMode());
        props.setProperty("connectTimeout", String.valueOf(Math.max(1, t.timeout().toSeconds())));
        props.setProperty("socketTimeout", String.valueOf(Math.max(5, t.timeout().toSeconds() * 2)));
        props.setProperty("ApplicationName", "iam-worker");
        props.setProperty("tcpKeepAlive", "true");
        if (t.caCertificatePem() != null) {
            ca = Files.createTempFile("iam-pg-ca", ".pem", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(ca, t.caCertificatePem(), StandardCharsets.US_ASCII);
            props.setProperty("sslrootcert", ca.toString());
        }
        char[] pw = password.reveal(); // provider credential use: JDBC login only, cleared below
        props.setProperty("password", new String(pw));
        Arrays.fill(pw, '\0');
        String url = "jdbc:postgresql://" + t.host() + ":" + t.port() + "/" + t.database();
        try {
            Connection c = DriverManager.getConnection(url, props);
            c.setAutoCommit(true);
            return new Session(c, ca);
        } catch (SQLException e) {
            deleteQuietly(ca);
            String state = e.getSQLState() == null ? "" : e.getSQLState();
            if (state.startsWith("28")) {
                throw new DbSession.AuthenticationException("login rejected for " + t.username());
            }
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
            if (msg.contains("ssl") || msg.contains("certificate") || msg.contains("hostname")) {
                throw new DbSession.AuthenticationException("TLS verification failed for " + t.host() + " (check sslMode / CA)");
            }
            throw new DbSession.ConnectionException("cannot connect to " + t.host() + ":" + t.port() + " (SQLState " + state + ")");
        } finally {
            props.remove("password");
        }
    }

    private static void deleteQuietly(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                // tmpfs, removed with the container
            }
        }
    }

    private record Session(Connection c, Path ca) implements DbSession {

        @Override
        public List<Map<String, Object>> query(String sql, Object... params) throws IOException {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    ps.setObject(i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int k = 1; k <= md.getColumnCount(); k++) {
                            Object v = rs.getObject(k);
                            if (v instanceof java.sql.Array a) {
                                v = Arrays.asList((Object[]) a.getArray());
                            } else if (v instanceof java.sql.Timestamp ts) {
                                v = ts.toInstant();
                            } else if (v instanceof java.time.OffsetDateTime odt) {
                                v = odt.toInstant();
                            }
                            row.put(md.getColumnLabel(k).toLowerCase(Locale.ROOT), v);
                        }
                        rows.add(row);
                    }
                    return rows;
                }
            } catch (SQLException e) {
                throw new DbSession.StatementException(e.getSQLState(), "query failed (SQLState " + e.getSQLState() + ")");
            }
        }

        @Override
        public void execute(String statement) throws IOException {
            try (Statement st = c.createStatement()) {
                st.execute(statement); // nosemgrep: statement is produced by the server with format(%I) from a bound parameter
            } catch (SQLException e) {
                throw new DbSession.StatementException(e.getSQLState(), "statement refused (SQLState " + e.getSQLState() + ")");
            }
        }

        @Override
        public void close() {
            try {
                c.close();
            } catch (SQLException e) {
                // closing
            }
            deleteQuietly(ca);
        }
    }
}
