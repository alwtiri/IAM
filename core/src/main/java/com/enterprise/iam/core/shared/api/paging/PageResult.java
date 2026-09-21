package com.enterprise.iam.core.shared.api.paging;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** A page of results plus the cursor for the next page (null on the last page). */
public record PageResult<T>(List<T> items, String nextCursor, int limit) {

    public PageResult {
        items = List.copyOf(items);
    }

    /**
     * Builds a page from a query that fetched {@code limit + 1} rows: the extra row only signals that more exist.
     */
    public static <T> PageResult<T> fromOverfetch(List<T> rows, int limit, Function<T, UUID> idOf) {
        if (rows.size() > limit) {
            List<T> page = rows.subList(0, limit);
            return new PageResult<>(page, PageRequest.encode(idOf.apply(page.get(limit - 1))), limit);
        }
        return new PageResult<>(rows, null, limit);
    }

    public <R> PageResult<R> map(Function<T, R> mapper) {
        return new PageResult<>(items.stream().map(mapper).toList(), nextCursor, limit);
    }
}
