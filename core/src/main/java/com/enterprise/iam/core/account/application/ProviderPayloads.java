package com.enterprise.iam.core.account.application;

import com.enterprise.iam.core.account.domain.Account;
import com.enterprise.iam.core.account.domain.DiscoveredAccount;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps worker result payloads (serialized SPI {@code AccountState}) to {@link DiscoveredAccount}s. Shape per account:
 * {@code {"account":{"nativeId","name"},"status","privileged","groups":[{"nativeId","name"}],"lastLogin","passwordLastSet",
 * "attributes":{...}}}; {@code attributes.displayName} and {@code attributes.privilegeReason} are lifted out.
 * Malformed entries are skipped (never partially imported).
 */
public final class ProviderPayloads {

    private ProviderPayloads() {
    }

    public static List<DiscoveredAccount> accounts(Map<String, Object> payload) {
        List<DiscoveredAccount> out = new ArrayList<>();
        if (!(payload.get("accounts") instanceof List<?> list)) {
            return out;
        }
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                DiscoveredAccount d = account(m);
                if (d != null) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    /** Single observed account state (lifecycle operation result: {@code {"account": {...state...}}}). */
    public static DiscoveredAccount observed(Map<String, Object> payload) {
        return payload.get("state") instanceof Map<?, ?> m ? account(m) : null;
    }

    static DiscoveredAccount account(Map<?, ?> m) {
        if (!(m.get("account") instanceof Map<?, ?> ref)) {
            return null;
        }
        String name = str(ref.get("name"));
        String nativeId = str(ref.get("nativeId"));
        if (name == null || name.isBlank()) {
            return null;
        }
        if (nativeId == null || nativeId.isBlank()) {
            nativeId = name;
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        if (m.get("attributes") instanceof Map<?, ?> a) {
            a.forEach((k, v) -> {
                String key = String.valueOf(k);
                String lk = key.toLowerCase(java.util.Locale.ROOT);
                if (v != null && !(lk.contains("password") || lk.contains("secret") || lk.contains("token") || lk.contains("privatekey"))) {
                    attributes.put(key, String.valueOf(v));
                }
            });
        }
        String displayName = attributes.remove("displayName");
        String privilegeReason = attributes.remove("privilegeReason");
        List<String> groups = new ArrayList<>();
        if (m.get("groups") instanceof List<?> gl) {
            for (Object g : gl) {
                if (g instanceof Map<?, ?> gm && str(gm.get("nativeId")) != null) {
                    groups.add(str(gm.get("nativeId")));
                }
            }
        }
        return new DiscoveredAccount(nativeId, name, displayName, status(str(m.get("status"))), Boolean.TRUE.equals(m.get("privileged")),
                privilegeReason, instant(m.get("lastLogin")), instant(m.get("passwordLastSet")), attributes, groups);
    }

    static Account.NativeStatus status(String s) {
        if (s == null) {
            return Account.NativeStatus.UNKNOWN;
        }
        try {
            return Account.NativeStatus.valueOf(s);
        } catch (IllegalArgumentException e) {
            return Account.NativeStatus.UNKNOWN;
        }
    }

    private static Instant instant(Object v) {
        try {
            return v == null ? null : Instant.parse(v.toString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String str(Object v) {
        return v == null ? null : v.toString();
    }
}
