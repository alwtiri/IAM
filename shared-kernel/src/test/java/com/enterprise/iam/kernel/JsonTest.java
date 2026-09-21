package com.enterprise.iam.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {

    @Test
    void canonicalFormSortsKeysDeterministically() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("b", 1);
        a.put("a", Map.of("z", "x", "y", List.of(true, false)));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("a", Map.of("y", List.of(true, false), "z", "x"));
        b.put("b", 1);
        assertEquals(Json.writeCanonical(a), Json.writeCanonical(b));
        assertEquals("{\"a\":{\"y\":[true,false],\"z\":\"x\"},\"b\":1}", Json.writeCanonical(a));
    }

    @Test
    void escapesControlAndQuoteCharacters() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", Json.write("a\"b\\c\n\u0001"));
    }

    @Test
    void roundTripsParsedValues() {
        Map<String, Object> m = Json.parseObject("{\"auth\":{\"client_token\":\"hvs.x\",\"lease_duration\":3600,\"renewable\":true},\"n\":null,\"l\":[1,\"\\u00e9\"]}");
        assertEquals("hvs.x", Json.path(m, "auth", "client_token"));
        assertEquals(new BigDecimal("3600"), Json.path(m, "auth", "lease_duration"));
        assertEquals(Boolean.TRUE, Json.path(m, "auth", "renewable"));
        assertEquals("é", ((List<?>) m.get("l")).get(1));
        assertTrue(m.containsKey("n"));
    }

    @Test
    void rejectsMalformedInputAndSecrets() {
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{\"a\":}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[1,2"));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("{} x"));
        assertThrows(IllegalArgumentException.class, () -> Json.write(Map.of("p", Secret.of("x"))));
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(100) + "]".repeat(100)));
    }
}
