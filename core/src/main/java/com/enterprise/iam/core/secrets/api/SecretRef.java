package com.enterprise.iam.core.secrets.api;

import java.util.Objects;
import java.util.regex.Pattern;

/** Reference to a secret version in Vault, e.g. {@code vault:iam/providers/<id>/connection#3}. Never contains the value. */
public record SecretRef(String value) {

    private static final Pattern FORMAT = Pattern.compile("^vault:[a-z0-9-]+/[a-z0-9/_-]+#[0-9]+$");

    public SecretRef {
        Objects.requireNonNull(value, "value");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid secret reference");
        }
    }

    public static SecretRef of(String mount, String path, long version) {
        return new SecretRef("vault:" + mount + "/" + path + "#" + version);
    }

    public String mount() {
        return value.substring(6, value.indexOf('/'));
    }

    public String path() {
        return value.substring(value.indexOf('/') + 1, value.indexOf('#'));
    }

    public long version() {
        return Long.parseLong(value.substring(value.indexOf('#') + 1));
    }
}
