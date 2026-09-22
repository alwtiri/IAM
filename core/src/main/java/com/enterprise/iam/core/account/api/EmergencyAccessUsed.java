package com.enterprise.iam.core.account.api;

import java.time.Instant;
import java.util.UUID;

/** Published when someone breaks the glass on an emergency account. Security is notified; the use must be reviewed. */
public record EmergencyAccessUsed(UUID checkoutId, UUID accountId, String accountLabel, UUID identityId, String identityName, String reason,
                                  Instant notAfter) {
}
