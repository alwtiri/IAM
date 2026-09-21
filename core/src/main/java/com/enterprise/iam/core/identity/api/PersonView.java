package com.enterprise.iam.core.identity.api;

import java.time.LocalDate;
import java.util.UUID;

public record PersonView(UUID id, UUID orgUnitId, String employeeId, String givenName, String familyName, String displayName,
                         UUID positionId, UUID locationId, UUID managerPersonId, String employmentStatus, LocalDate startDate,
                         LocalDate endDate, String email, String phone, long version) {
}
