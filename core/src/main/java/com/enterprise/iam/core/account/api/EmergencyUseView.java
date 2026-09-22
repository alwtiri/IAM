package com.enterprise.iam.core.account.api;

import java.time.Instant;
import java.util.UUID;

/** One break-glass use with its review state. */
public record EmergencyUseView(CheckoutView checkout, String reviewStatus, UUID reviewedBy, String reviewedByName, Instant reviewedAt,
                               String reviewNote) {
}
