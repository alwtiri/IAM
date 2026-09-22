package com.enterprise.iam.core.notification.domain;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain-text e-mail templates (spec §57). Values are inserted verbatim into the text body; header values (subject)
 * are stripped of CR/LF to prevent header injection. Unknown placeholders render empty, never as raw markup.
 */
public final class NotificationTemplates {

    public record Rendered(String subject, String body) {
    }

    private record Template(String subject, String body) {
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([a-zA-Z0-9_]+)}}");

    private static final Map<String, Template> TEMPLATES = Map.of(
            "role-assignment.granted", new Template("Access granted: {{role}}",
                    "Hello {{displayName}},\n\nThe role {{role}} has been granted to your identity {{username}}.\n"
                            + "If you did not expect this change, contact your security team.\n\nReference: {{reference}}\n"),
            "role-assignment.revoked", new Template("Access removed: {{role}}",
                    "Hello {{displayName}},\n\nThe role {{role}} has been removed from your identity {{username}}.\n\nReference: {{reference}}\n"),
            "role-assignment.expired", new Template("Access expired: {{role}}",
                    "Hello {{displayName}},\n\nYour time-limited role {{role}} on identity {{username}} has expired.\n\nReference: {{reference}}\n"),
            "access-request.pending", new Template("Approval needed: {{role}} for {{requester}}",
                    "Hello {{displayName}},\n\n{{requester}} requested the role {{role}} and your approval is needed.\n"
                            + "Open the platform: Identity & Access > Approvals.\n\nReference: {{reference}}\n"),
            "access-request.decided", new Template("Your access request for {{role}}: {{status}}",
                    "Hello {{displayName}},\n\nYour request for the role {{role}} is now {{status}}.\n{{reason}}\n\nReference: {{reference}}\n"),
            "identity.lifecycle", new Template("Identity {{username}} is now {{state}}",
                    "Hello {{displayName}},\n\nYour identity {{username}} changed from {{from}} to {{state}}.\nReason: {{reason}}\n\n"
                            + "Reference: {{reference}}\n"));

    private NotificationTemplates() {
    }

    public static boolean exists(String key) {
        return TEMPLATES.containsKey(key);
    }

    public static Rendered render(String key, Map<String, String> values) {
        Template t = Objects.requireNonNull(TEMPLATES.get(key), () -> "unknown template " + key);
        String subject = fill(t.subject(), values).replaceAll("[\\r\\n]+", " ").strip();
        return new Rendered(subject.length() > 200 ? subject.substring(0, 200) : subject, fill(t.body(), values));
    }

    private static String fill(String text, Map<String, String> values) {
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = values.getOrDefault(m.group(1), "");
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? "" : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
