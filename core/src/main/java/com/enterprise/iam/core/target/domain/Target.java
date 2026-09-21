package com.enterprise.iam.core.target.domain;

import com.enterprise.iam.kernel.ApiError;
import com.enterprise.iam.kernel.IamException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A managed resource (spec §10). Phase 2 stores metadata only; discovery, reconciliation and health are updated by
 * providers from Phase 3. {@code Windows Server} and {@code Active Directory} are distinct types (spec §14).
 */
public record Target(UUID id, String name, String hostname, String ipAddress, String dnsName, Type type, String platform,
                     String operatingSystem, String environment, Criticality criticality, Classification classification,
                     UUID ownerOrgUnitId, UUID ownerIdentityId, UUID technicalOwnerIdentityId, UUID businessOwnerIdentityId,
                     UUID locationId, List<String> tags, Status status, long version) {

    public enum Type {
        LINUX_SERVER, WINDOWS_SERVER, ACTIVE_DIRECTORY, DATABASE, APPLICATION, NETWORK_DEVICE, VMWARE, OVM, HPE_3PAR,
        HPE_STOREONCE, HPE_ONEVIEW, CLOUD_PLATFORM, KUBERNETES, CONTAINER_PLATFORM, API_ENDPOINT
    }

    public enum Criticality { LOW, MEDIUM, HIGH, CRITICAL }

    public enum Classification { PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED }

    public enum Status { ACTIVE, MAINTENANCE, DECOMMISSIONED }

    public static final Set<String> ENVIRONMENTS = Set.of("PRODUCTION", "STAGING", "TEST", "DEVELOPMENT", "DR");
    private static final Pattern HOST = Pattern.compile("^(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}))*$");
    private static final Pattern IP = Pattern.compile("^([0-9]{1,3}(\\.[0-9]{1,3}){3}|[0-9A-Fa-f:]{2,39})$");
    private static final Pattern TAG = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");

    public Target {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        List<ApiError.FieldIssue> issues = new ArrayList<>();
        if (name == null || name.isBlank() || name.length() > 200) {
            issues.add(new ApiError.FieldIssue("name", "INVALID", "required, max 200 characters"));
        }
        if (hostname != null && !HOST.matcher(hostname).matches()) {
            issues.add(new ApiError.FieldIssue("hostname", "INVALID", "not a valid host name"));
        }
        if (dnsName != null && !HOST.matcher(dnsName).matches()) {
            issues.add(new ApiError.FieldIssue("dnsName", "INVALID", "not a valid DNS name"));
        }
        if (ipAddress != null && !IP.matcher(ipAddress).matches()) {
            issues.add(new ApiError.FieldIssue("ipAddress", "INVALID", "not a valid IP address"));
        }
        if (environment == null || !ENVIRONMENTS.contains(environment)) {
            issues.add(new ApiError.FieldIssue("environment", "INVALID", "one of " + ENVIRONMENTS));
        }
        if (ownerOrgUnitId == null) {
            issues.add(new ApiError.FieldIssue("ownerOrgUnitId", "REQUIRED", "every target needs an owning org unit"));
        }
        tags = tags == null ? List.of() : List.copyOf(tags);
        if (tags.size() > 50 || tags.stream().anyMatch(t -> !TAG.matcher(t).matches())) {
            issues.add(new ApiError.FieldIssue("tags", "INVALID", "max 50 tags of 1-64 characters [A-Za-z0-9_.:-]"));
        }
        if (!issues.isEmpty()) {
            throw IamException.validation(issues);
        }
        criticality = criticality == null ? Criticality.MEDIUM : criticality;
        classification = classification == null ? Classification.INTERNAL : classification;
        status = status == null ? Status.ACTIVE : status;
    }
}
