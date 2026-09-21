package com.enterprise.iam.provider.spi.model;

/** Counts from a full, read-only inventory pass. */
public record DiscoverySummary(long accounts, long groups, long targets) {
}
