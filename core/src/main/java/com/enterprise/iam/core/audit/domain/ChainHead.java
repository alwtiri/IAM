package com.enterprise.iam.core.audit.domain;

import java.util.Objects;

/** Last position of a chain partition; locked FOR UPDATE while appending. */
public record ChainHead(String chainPartition, long lastSeq, byte[] lastHash) {

    public ChainHead {
        Objects.requireNonNull(chainPartition, "chainPartition");
        Objects.requireNonNull(lastHash, "lastHash");
        if (lastHash.length != 32) {
            throw new IllegalArgumentException("lastHash must be 32 bytes");
        }
        lastHash = lastHash.clone();
    }

    public static ChainHead genesis(String partition) {
        return new ChainHead(partition, 0, HashChain.GENESIS);
    }

    @Override
    public byte[] lastHash() {
        return lastHash.clone();
    }
}
