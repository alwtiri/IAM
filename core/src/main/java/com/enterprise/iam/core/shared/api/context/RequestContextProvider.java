package com.enterprise.iam.core.shared.api.context;

/** Supplies the {@link RequestContext}; web requests use the correlation filter, jobs generate their own id. */
public interface RequestContextProvider {
    RequestContext current();
}
