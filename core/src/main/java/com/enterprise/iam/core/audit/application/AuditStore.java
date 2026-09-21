package com.enterprise.iam.core.audit.application;

import com.enterprise.iam.core.audit.api.AuditSearch;
import com.enterprise.iam.core.audit.domain.AuditEvent;
import com.enterprise.iam.core.audit.domain.ChainHead;
import com.enterprise.iam.core.shared.api.paging.PageRequest;
import java.util.List;
import java.util.function.Consumer;

/** Persistence port for the audit chain. Must be called inside a transaction. */
public interface AuditStore {

    /** Locks (creating if absent) and returns the head of {@code partition}. */
    ChainHead lockHead(String partition);

    void insert(AuditEvent event);

    void updateHead(ChainHead head);

    /** Returns up to {@code page.limit()+1} events, newest first, for overfetch paging. */
    List<AuditEvent> search(AuditSearch filter, PageRequest page);

    /** Streams all events of a partition in ascending sequence order. */
    void streamPartition(String partition, Consumer<AuditEvent> consumer);

    ChainHead readHead(String partition);
}
