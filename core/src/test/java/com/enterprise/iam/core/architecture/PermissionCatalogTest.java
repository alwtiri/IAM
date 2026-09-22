package com.enterprise.iam.core.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enterprise.iam.core.shared.api.security.Permissions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** The permission catalog in code and the migration seeds (in version order) must be identical, and every built-in role exists (spec §19). */
class PermissionCatalogTest {

    private static Path migrationDir() {
        Path p = Path.of("src/main/resources/db/migration");
        return Files.isDirectory(p) ? p : Path.of("core").resolve(p);
    }

    private static String migration() throws IOException {
        return Files.readString(migrationDir().resolve("V6__authorization.sql"), StandardCharsets.UTF_8);
    }

    /** Migration files in version order (V6 before V11). */
    private static List<Path> migrations() throws IOException {
        try (var files = Files.list(migrationDir())) {
            return files.filter(f -> f.getFileName().toString().matches("V\\d+__.*\\.sql"))
                    .sorted(java.util.Comparator.comparingInt(f -> Integer.parseInt(f.getFileName().toString().substring(1).split("__")[0])))
                    .toList();
        }
    }

    @Test
    void seededPermissionsEqualCodeCatalog() throws IOException {
        List<String> seeded = new ArrayList<>();
        Pattern code = Pattern.compile("\\('([a-z:\\-]+)',");
        for (Path f : migrations()) {
            String sql = Files.readString(f, StandardCharsets.UTF_8);
            int start = sql.indexOf("INSERT INTO \"authorization\".permission");
            if (start < 0) {
                continue;
            }
            int end = sql.indexOf(";", start);
            Matcher m = code.matcher(sql.substring(start, end));
            while (m.find()) {
                seeded.add(m.group(1));
            }
        }
        assertEquals(Permissions.ALL, seeded);
    }

    @Test
    void allSpecRolesAreSeeded() throws IOException {
        String sql = migration();
        for (String role : List.of("PLATFORM_ADMINISTRATOR", "IAM_ADMINISTRATOR", "SECURITY_ADMINISTRATOR", "INFRASTRUCTURE_ADMINISTRATOR",
                "PAM_ADMINISTRATOR", "AUDITOR", "HELPDESK", "APPLICATION_ADMINISTRATOR", "DATABASE_ADMINISTRATOR", "NETWORK_ADMINISTRATOR",
                "READ_ONLY_AUDITOR")) {
            assertTrue(sql.contains("'" + role + "'"), role + " missing");
        }
    }
}
