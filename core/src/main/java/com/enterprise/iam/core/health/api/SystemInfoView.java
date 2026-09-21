package com.enterprise.iam.core.health.api;

import java.util.List;

public record SystemInfoView(String version, String apiVersion, String buildSha, List<String> enabledExtensions) {
}
