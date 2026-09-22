package com.enterprise.iam.providers.ad;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * One bound LDAP connection to a domain controller. Filters passed to {@link #search} are built only from constants and
 * values escaped with {@link AdValues#escapeFilterValue}; DNs come from the directory itself.
 */
public interface LdapDirectory extends AutoCloseable {

    enum Scope { BASE, SUBTREE }

    /** One entry. Attribute names are matched case-insensitively; {@code objectGUID} is returned in canonical GUID form. */
    record Entry(String dn, Map<String, List<String>> values) {
        public Entry {
            java.util.TreeMap<String, List<String>> m = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            values.forEach((k, v) -> m.put(k, List.copyOf(v)));
            values = java.util.Collections.unmodifiableMap(m);
        }

        public String first(String attribute) {
            List<String> v = values.get(attribute);
            return v == null || v.isEmpty() ? null : v.get(0);
        }

        public List<String> all(String attribute) {
            return values.getOrDefault(attribute, List.of());
        }
    }

    /** One page of a paged search; {@code cookie} is null on the last page. Cookies are valid on this connection only. */
    record SearchPage(List<Entry> entries, byte[] cookie) {
    }

    /** The server cannot be reached or dropped the connection (retryable). */
    final class ConnectionException extends IOException {
        private static final long serialVersionUID = 1L;

        public ConnectionException(String message) {
            super(message);
        }
    }

    /** Bind rejected or TLS trust / host name verification failed (not retryable). */
    final class AuthenticationException extends IOException {
        private static final long serialVersionUID = 1L;

        public AuthenticationException(String message) {
            super(message);
        }
    }

    /** The server answered a request with an error result (the change was not applied). */
    final class RejectedException extends IOException {
        private static final long serialVersionUID = 1L;
        private final String resultCode;

        public RejectedException(String resultCode, String message) {
            super(message);
            this.resultCode = resultCode;
        }

        public String resultCode() {
            return resultCode;
        }
    }

    /** No response within the timeout: a modify may or may not have been applied. */
    final class TimeoutException extends IOException {
        private static final long serialVersionUID = 1L;

        public TimeoutException(String message) {
            super(message);
        }
    }

    /** Reads the root DSE (no bind requirements beyond the connection). */
    Entry rootDse(List<String> attributes) throws IOException;

    /** Paged search. A missing base object yields an empty page, not an error. */
    SearchPage search(String baseDn, Scope scope, String filter, List<String> attributes, int pageSize, byte[] cookie) throws IOException;

    /** Replaces all values of one attribute with a single value. */
    void replace(String dn, String attribute, String value) throws IOException;

    /** Replaces an attribute with one binary value (e.g. {@code unicodePwd}). */
    default void replaceBinary(String dn, String attribute, byte[] value) throws IOException {
        throw new RejectedException("unwillingToPerform", "binary modifications are not supported by this directory client");
    }

    @Override
    void close();
}
