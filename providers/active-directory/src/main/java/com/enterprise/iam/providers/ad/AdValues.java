package com.enterprise.iam.providers.ad;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/** Active Directory value conversions (FILETIME, userAccountControl, objectGUID) and RFC 4515 filter escaping. */
final class AdValues {

    /** userAccountControl: ACCOUNTDISABLE. */
    static final long UF_ACCOUNTDISABLE = 0x2;
    /** userAccountControl: DONT_EXPIRE_PASSWORD. */
    static final long UF_DONT_EXPIRE_PASSWD = 0x10000;
    /** msDS-User-Account-Control-Computed: LOCKOUT. */
    static final long UF_LOCKOUT = 0x10;
    /** msDS-User-Account-Control-Computed: PASSWORD_EXPIRED. */
    static final long UF_PASSWORD_EXPIRED = 0x800000;

    /** 100-ns intervals between 1601-01-01 and 1970-01-01. */
    private static final long EPOCH_DIFF_100NS = 116_444_736_000_000_000L;
    private static final Pattern GUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    /** sAMAccountName: at most 20 characters, none of " / \ [ ] : ; | = , + * ? < > (and no control characters). */
    private static final Pattern SAM = Pattern.compile("^[^\"/\\\\\\[\\]:;|=,+*?<>\\p{Cntrl}]{1,20}$");

    private AdValues() {
    }

    /** FILETIME (100-ns since 1601) → instant; null for 0, "never" (Long.MAX_VALUE) and unparsable values. */
    static Instant fileTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        long v;
        try {
            v = Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
        if (v <= 0 || v == Long.MAX_VALUE) {
            return null;
        }
        long sinceEpoch = v - EPOCH_DIFF_100NS;
        return Instant.ofEpochSecond(Math.floorDiv(sinceEpoch, 10_000_000L), Math.floorMod(sinceEpoch, 10_000_000L) * 100);
    }

    static long parseFlags(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** objectGUID bytes (little-endian first three fields) → canonical string. */
    static String guid(byte[] b) {
        if (b == null || b.length != 16) {
            return null;
        }
        return String.format(Locale.ROOT, "%02x%02x%02x%02x-%02x%02x-%02x%02x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                b[3], b[2], b[1], b[0], b[5], b[4], b[7], b[6], b[8], b[9], b[10], b[11], b[12], b[13], b[14], b[15]);
    }

    static boolean isGuid(String s) {
        return s != null && GUID.matcher(s).matches();
    }

    static boolean isSamAccountName(String s) {
        return s != null && SAM.matcher(s).matches() && !s.endsWith(".") && !s.isBlank();
    }

    /** RFC 4515 escaping of an assertion value. */
    static String escapeFilterValue(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\5c");
                case '*' -> sb.append("\\2a");
                case '(' -> sb.append("\\28");
                case ')' -> sb.append("\\29");
                case '\0' -> sb.append("\\00");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /** First RDN value of a DN ({@code CN=Domain Admins,CN=Users,...} → {@code Domain Admins}), unescaped for display. */
    static String rdnValue(String dn) {
        if (dn == null) {
            return null;
        }
        int eq = dn.indexOf('=');
        if (eq < 0) {
            return dn;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = eq + 1; i < dn.length(); i++) {
            char c = dn.charAt(i);
            if (c == '\\' && i + 1 < dn.length()) {
                sb.append(dn.charAt(++i));
            } else if (c == ',' || c == '+') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
