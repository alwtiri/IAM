package com.enterprise.iam.core.operation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.enterprise.iam.core.operation.domain.Operation;
import com.enterprise.iam.core.operation.domain.OperationStatus;
import com.enterprise.iam.kernel.IamException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OperationTest {

    private final Instant now = Instant.parse("2026-09-21T10:00:00Z");

    @Test
    void mutatingSuccessRequiresVerification() {
        Operation op = new Operation(UUID.randomUUID(), "ACCOUNT_DISABLE", true, "k1", 3);
        op.start(now);
        assertThrows(IllegalStateException.class, () -> op.succeed(null, now));
        op.succeed(new Operation.Verification("READ_BACK", now, "status=DISABLED"), now);
        assertEquals(OperationStatus.SUCCESS, op.status());
    }

    @Test
    void cannotSucceedWithoutRunning() {
        Operation op = new Operation(UUID.randomUUID(), "DISCOVER", false, "k2", 3);
        assertThrows(IamException.class, () -> op.succeed(null, now));
    }

    @Test
    void retriesAreBounded() {
        Operation op = new Operation(UUID.randomUUID(), "ACCOUNT_CREATE", true, "k3", 2);
        op.start(now);
        op.fail("CONNECTION_REFUSED", "down", now);
        op.retry();
        assertEquals(2, op.attempt());
        op.start(now);
        op.timeout("slow", now);
        assertThrows(IamException.class, op::retry);
    }

    @Test
    void unknownOutcomeMustBeResolvedByVerification() {
        Operation op = new Operation(UUID.randomUUID(), "PASSWORD_ROTATE", true, "k4", 3);
        op.start(now);
        op.unknown("no response", now);
        assertThrows(IamException.class, op::retry);
        op.succeed(new Operation.Verification("LOGIN_TEST", now, "login ok"), now);
        assertEquals(OperationStatus.SUCCESS, op.status());
    }
}
