// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import static com.hedera.hapi.node.base.ResponseCodeEnum.MEMO_TOO_LONG;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.spi.fees.FeeCharging;
import com.hedera.node.app.spi.workflows.record.StreamBuilder;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class HandleExceptionTest {
    @Test
    void reportsItsGivenStatus() {
        final var ex = new HandleException(MEMO_TOO_LONG);

        assertEquals(MEMO_TOO_LONG, ex.getStatus());
    }

    @Test
    void trueIsntProblematic() {
        assertDoesNotThrow(() -> HandleException.validateTrue(true, MEMO_TOO_LONG));
    }

    @Test
    void falseIsProblem() {
        final var failure =
                assertThrows(HandleException.class, () -> HandleException.validateTrue(false, MEMO_TOO_LONG));

        assertEquals(MEMO_TOO_LONG, failure.getStatus());
    }

    @Test
    void trueIsProblemFromOtherPerspective() {
        final var failure =
                assertThrows(HandleException.class, () -> HandleException.validateFalse(true, MEMO_TOO_LONG));

        assertEquals(MEMO_TOO_LONG, failure.getStatus());
    }

    @Test
    void falseIsOkFromOtherPerspective() {
        assertDoesNotThrow(() -> HandleException.validateFalse(false, MEMO_TOO_LONG));
    }

    @Test
    void replaysWhenRollbackCallbackIsPresent() {
        final var replayed = new AtomicBoolean(false);
        final var ex = new HandleException(MEMO_TOO_LONG, (ctx, dispatch) -> replayed.set(true));
        final var childDispatch = new HandleException.ChildDispatch() {
            @Override
            public <T extends StreamBuilder> T dispatch(final DispatchOptions<T> options) {
                return null;
            }
        };

        ex.maybeReplay(mock(FeeCharging.Context.class), childDispatch);

        assertTrue(replayed.get());
    }
}
