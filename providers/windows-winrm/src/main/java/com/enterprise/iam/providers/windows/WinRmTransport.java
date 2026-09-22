package com.enterprise.iam.providers.windows;

import com.enterprise.iam.kernel.Secret;
import java.io.IOException;
import java.time.Duration;

/** Runs PowerShell on a Windows host through WinRM (production: {@link HttpWinRmTransport}; tests: fakes). */
public interface WinRmTransport {

    /** Result of one PowerShell run. */
    record Result(int exitCode, String stdout, String stderr) {
        public boolean ok() {
            return exitCode == 0;
        }
    }

    /** Connection settings (never secrets). */
    record Endpoint(String url, String username, String caCertificatesPem, String pinnedCertificateSha256, Duration timeout) {
    }

    /** Host unreachable or refused (retryable). */
    final class ConnectionException extends IOException {
        private static final long serialVersionUID = 1L;

        public ConnectionException(String message) {
            super(message);
        }
    }

    /** HTTP 401/403 or TLS trust failure (not retryable). */
    final class AuthenticationException extends IOException {
        private static final long serialVersionUID = 1L;

        public AuthenticationException(String message) {
            super(message);
        }
    }

    /**
     * Runs a script. The script is built from constants only; variable input travels in {@code parametersJson}, which the
     * transport hands to the script base64-encoded (never spliced into the script text).
     */
    Result run(Endpoint endpoint, Secret password, String script, String parametersJson) throws IOException;
}
