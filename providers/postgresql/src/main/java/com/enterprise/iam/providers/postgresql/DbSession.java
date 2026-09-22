package com.enterprise.iam.providers.postgresql;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** One authenticated database session (production: {@link JdbcDbSessions}; tests: fakes). */
public interface DbSession extends AutoCloseable {

    /** Parameterised query; column names are lower case. */
    List<Map<String, Object>> query(String sql, Object... params) throws IOException;

    /** Executes a statement built server-side with {@code format('%I')} from {@link #query}; never concatenated in Java. */
    void execute(String statement) throws IOException;

    @Override
    void close();

    /** Unreachable host or refused connection (retryable). */
    final class ConnectionException extends IOException {
        private static final long serialVersionUID = 1L;

        public ConnectionException(String message) {
            super(message);
        }
    }

    /** Bad credentials or TLS verification failure (not retryable). */
    final class AuthenticationException extends IOException {
        private static final long serialVersionUID = 1L;

        public AuthenticationException(String message) {
            super(message);
        }
    }

    /** The server refused a statement (e.g. insufficient privilege). */
    final class StatementException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String sqlState;

        public StatementException(String sqlState, String message) {
            super(message);
            this.sqlState = sqlState;
        }

        public String sqlState() {
            return sqlState;
        }
    }
}
