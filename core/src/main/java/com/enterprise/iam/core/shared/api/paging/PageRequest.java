package com.enterprise.iam.core.shared.api.paging;

import com.enterprise.iam.kernel.IamException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * Keyset pagination request (API-GUIDELINES §4). The cursor encodes the last seen UUIDv7 id; ids are time-ordered,
 * so {@code id > cursor ORDER BY id} is stable and index-friendly.
 */
public record PageRequest(int limit, UUID after) {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    public PageRequest {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw IamException.validation("limit", "OUT_OF_RANGE", "limit must be between 1 and " + MAX_LIMIT);
        }
    }

    public static PageRequest of(Integer limit, String cursor) {
        return new PageRequest(limit == null ? DEFAULT_LIMIT : limit, decode(cursor));
    }

    public static String encode(UUID lastId) {
        return lastId == null ? null
                : Base64.getUrlEncoder().withoutPadding().encodeToString(lastId.toString().getBytes(StandardCharsets.US_ASCII));
    }

    static UUID decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException e) {
            throw IamException.validation("cursor", "INVALID", "cursor is not valid");
        }
    }
}
