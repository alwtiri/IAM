package com.enterprise.iam.providers.linux;

import java.io.IOException;
import java.time.Duration;

/** One authenticated SSH session to a Linux target; commands run through exec channels. */
public interface SshTransport extends AutoCloseable {

    /** Result of one remote command. */
    record Exec(int exitCode, String stdout, String stderr) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /** Thrown when the target cannot be reached or refuses the connection (retryable). */
    final class ConnectionException extends IOException {
        private static final long serialVersionUID = 1L;

        public ConnectionException(String message) {
            super(message);
        }
    }

    /** Thrown when authentication or host-key verification fails (not retryable). */
    final class AuthenticationException extends IOException {
        private static final long serialVersionUID = 1L;

        public AuthenticationException(String message) {
            super(message);
        }
    }

    /**
     * Runs a command. {@code command} is built only from constants and validated, shell-quoted arguments.
     *
     * @param stdin optional bytes written to the command's standard input (never part of the command line)
     */
    Exec exec(String command, byte[] stdin, Duration timeout) throws IOException;

    @Override
    void close();
}
