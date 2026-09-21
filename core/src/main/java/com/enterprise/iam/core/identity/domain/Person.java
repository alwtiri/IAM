package com.enterprise.iam.core.identity.domain;

import com.enterprise.iam.kernel.ApiError;
import com.enterprise.iam.kernel.IamException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** The human or organizational owner of identities (spec §6). Never authenticates. */
public record Person(UUID id, UUID orgUnitId, String employeeId, String givenName, String familyName, String displayName,
                     UUID positionId, UUID locationId, UUID managerPersonId, EmploymentStatus employmentStatus,
                     LocalDate startDate, LocalDate endDate, String email, String phone, long version) {

    public enum EmploymentStatus { PRE_HIRE, ACTIVE, ON_LEAVE, TERMINATED }

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]{1,64}@[^@\\s]{1,255}\\.[^@\\s]{2,}$");
    private static final Pattern EMPLOYEE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");

    public Person {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(employmentStatus, "employmentStatus");
        List<ApiError.FieldIssue> issues = new ArrayList<>();
        givenName = name("givenName", givenName, issues);
        familyName = name("familyName", familyName, issues);
        displayName = displayName == null || displayName.isBlank() ? (givenName + " " + familyName).strip() : displayName.strip();
        if (displayName.length() > 200) {
            issues.add(new ApiError.FieldIssue("displayName", "TOO_LONG", "max 200 characters"));
        }
        if (employeeId != null && !EMPLOYEE_ID.matcher(employeeId).matches()) {
            issues.add(new ApiError.FieldIssue("employeeId", "INVALID", "1-32 characters: letters, digits, _ or -"));
        }
        if (email != null && !EMAIL.matcher(email).matches()) {
            issues.add(new ApiError.FieldIssue("email", "INVALID", "not a valid e-mail address"));
        }
        if (phone != null && phone.length() > 40) {
            issues.add(new ApiError.FieldIssue("phone", "TOO_LONG", "max 40 characters"));
        }
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            issues.add(new ApiError.FieldIssue("endDate", "BEFORE_START", "end date must not be before start date"));
        }
        if (id.equals(managerPersonId)) {
            issues.add(new ApiError.FieldIssue("managerPersonId", "SELF", "a person cannot be their own manager"));
        }
        if (!issues.isEmpty()) {
            throw IamException.validation(issues);
        }
    }

    private static String name(String field, String value, List<ApiError.FieldIssue> issues) {
        if (value == null || value.isBlank() || value.length() > 100) {
            issues.add(new ApiError.FieldIssue(field, "INVALID", "required, max 100 characters"));
            return value == null ? "" : value;
        }
        return value.strip();
    }
}
