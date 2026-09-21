package com.enterprise.iam.provider.spi.model;

import java.util.List;

/** A page of discovery results; {@code nextCursor} is null on the last page. */
public record Page<T>(List<T> items, String nextCursor) {
    public Page {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public boolean hasMore() {
        return nextCursor != null;
    }
}
