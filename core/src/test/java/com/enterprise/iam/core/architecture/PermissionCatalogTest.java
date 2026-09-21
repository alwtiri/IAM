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

/** The permission catalog in code and the V6 seed must be identical, and every built-in role exists (spec §19). */
class PermissionCatalogTest {

    private static String migration() throws IOException {
        Path p = Path.of("src/main/resources/db/migration/V6__authorization.sql");
        if (!Files.exists(p)) {
            p = Path.of("core").resolve(p);
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void seededPermissionsEqualCodeCatalog() throws IOException {
        String sql = migration();
        String block = sql.substring(sql.indexOf("INSERT INTO \"authorization\".permission"), sql.indexOf("-- Built-in roles"));
        Matcher m = Pattern.compile("\\('([a-z:\\-]+)',").matcher(block);
        List<String> seeded = new ArrayList<>();
        while (m.find()) {
            seeded.add(m.group(1));
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
