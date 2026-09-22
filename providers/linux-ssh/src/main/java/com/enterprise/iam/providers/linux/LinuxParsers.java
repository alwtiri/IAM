package com.enterprise.iam.providers.linux;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Parsers for {@code getent}, {@code passwd -S}, {@code chage -l} and {@code lastlog} output (portable across distributions). */
final class LinuxParsers {

    record PasswdEntry(String name, long uid, long gid, String gecos, String home, String shell) {
    }

    record GroupEntry(String name, long gid, List<String> members) {
    }

    private static final DateTimeFormatter LASTLOG = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss Z yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter CHAGE = DateTimeFormatter.ofPattern("MMM dd, yyyy", Locale.ENGLISH);

    private LinuxParsers() {
    }

    static List<PasswdEntry> passwd(String text) {
        List<PasswdEntry> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] f = line.split(":", -1);
            if (f.length >= 7 && !f[0].isBlank()) {
                try {
                    out.add(new PasswdEntry(f[0], Long.parseLong(f[2]), Long.parseLong(f[3]), f[4], f[5], f[6].trim()));
                } catch (NumberFormatException e) {
                    // skip malformed line
                }
            }
        }
        return out;
    }

    static List<GroupEntry> groups(String text) {
        List<GroupEntry> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] f = line.split(":", -1);
            if (f.length >= 4 && !f[0].isBlank()) {
                try {
                    List<String> members = new ArrayList<>();
                    for (String m : f[3].trim().split(",")) {
                        if (!m.isBlank()) {
                            members.add(m.trim());
                        }
                    }
                    out.add(new GroupEntry(f[0], Long.parseLong(f[2]), members));
                } catch (NumberFormatException e) {
                    // skip malformed line
                }
            }
        }
        return out;
    }

    /** Groups of each user: supplementary memberships plus the primary group. */
    static Map<String, Set<String>> membership(List<PasswdEntry> users, List<GroupEntry> groups) {
        Map<Long, String> byGid = new LinkedHashMap<>();
        groups.forEach(g -> byGid.put(g.gid(), g.name()));
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (PasswdEntry u : users) {
            Set<String> set = new LinkedHashSet<>();
            String primary = byGid.get(u.gid());
            if (primary != null) {
                set.add(primary);
            }
            out.put(u.name(), set);
        }
        for (GroupEntry g : groups) {
            for (String m : g.members()) {
                out.computeIfAbsent(m, k -> new LinkedHashSet<>()).add(g.name());
            }
        }
        return out;
    }

    /**
     * {@code passwd -S} lines: {@code name STATUS date min max warn inactive}. STATUS: P/PS usable, L/LK locked, NP no password.
     *
     * @return user → status code (P, L or NP)
     */
    static Map<String, String> passwordStatus(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length >= 2) {
                String s = switch (f[1]) {
                    case "P", "PS" -> "P";
                    case "L", "LK" -> "L";
                    case "NP" -> "NP";
                    default -> null;
                };
                if (s != null) {
                    out.put(f[0], s);
                }
            }
        }
        return out;
    }

    /** {@code lastlog} output → user → last login (users that never logged in are absent). */
    static Map<String, Instant> lastLogins(String text) {
        Map<String, Instant> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            if (line.startsWith("Username") || line.contains("**Never logged in**") || line.isBlank()) {
                continue;
            }
            String name = line.split("\\s+", 2)[0];
            // The timestamp is the last 6 tokens: "Mon Sep 21 10:11:12 +0300 2026"
            String[] t = line.trim().split("\\s+");
            if (t.length >= 7) {
                String ts = String.join(" ", List.of(t).subList(t.length - 6, t.length));
                try {
                    out.put(name, java.time.ZonedDateTime.parse(ts, LASTLOG).toInstant());
                } catch (DateTimeParseException e) {
                    // unknown format: omit
                }
            }
        }
        return out;
    }

    /** {@code chage -l} "Account expires" value → expiry instant, or null for "never" / unknown. */
    static Instant accountExpiry(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("never")) {
            return null;
        }
        try {
            return LocalDate.parse(v, CHAGE).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Splits output of a multi-part script at marker lines {@code @@NAME@@}. */
    static Map<String, String> sections(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        String current = "";
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.startsWith("@@") && line.endsWith("@@") && line.length() > 4) {
                out.put(current, sb.toString());
                current = line.substring(2, line.length() - 2);
                sb.setLength(0);
            } else {
                sb.append(line).append('\n');
            }
        }
        out.put(current, sb.toString());
        return out;
    }
}
