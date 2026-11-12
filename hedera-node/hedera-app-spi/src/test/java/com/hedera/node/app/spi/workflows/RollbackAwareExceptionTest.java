// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.hedera.node.app.spi.fees.FeeCharging;
import org.junit.jupiter.api.Test;

class RollbackAwareExceptionTest {
    @Test
    void retainsInternalFailureAndReplaysWithWorkflowContexts() {
        final var cause = new IllegalStateException("Unexpected failure after execution");
        final var replay = mock(HandleException.OnRollback.class);
        final var feeContext = mock(FeeCharging.Context.class);
        final var handleContext = mock(HandleContext.class);
        final var failure = new RollbackAwareException(cause, replay);

        failure.maybeReplay(feeContext, handleContext);

        assertSame(cause, failure.getCause());
        verify(replay).replay(feeContext, handleContext);
    }

    @Test
    void requiresCauseAndRollbackCallback() {
        assertThrows(NullPointerException.class, () -> new RollbackAwareException(null, (_, _) -> {}));
        assertThrows(NullPointerException.class, () -> new RollbackAwareException(new IllegalStateException(), null));
    }
}
