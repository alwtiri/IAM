package com.enterprise.iam.core.provider.api;

import java.util.UUID;

/** A provider instance serving a target on a channel (e.g. {@code accounts}). */
public record ProviderBindingView(UUID targetId, UUID providerInstanceId, String providerType, String providerName, String channel) {
}
