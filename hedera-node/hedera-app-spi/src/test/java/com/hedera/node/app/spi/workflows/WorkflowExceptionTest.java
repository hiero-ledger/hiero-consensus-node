// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import static com.hedera.hapi.node.base.ResponseCodeEnum.MEMO_TOO_LONG;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

import com.hedera.hapi.node.base.ResponseCodeEnum;
import org.junit.jupiter.api.Test;

class WorkflowExceptionTest {
    @Test
    void testConstructor() {
        final var exception = new WorkflowException(ResponseCodeEnum.UNAUTHORIZED);

        assertThat(exception.getStatus()).isEqualTo(ResponseCodeEnum.UNAUTHORIZED);
        assertThat(exception.getMessage()).isEqualTo(ResponseCodeEnum.UNAUTHORIZED.protoName());
        assertTrue(exception.shouldRollbackStack());
    }

    @Test
    void testConstructorMultipleParams() {
        final var exception =
                new WorkflowException(ResponseCodeEnum.UNAUTHORIZED, WorkflowException.ShouldRollbackStack.NO);

        assertThat(exception.getStatus()).isEqualTo(ResponseCodeEnum.UNAUTHORIZED);
        assertThat(exception.getMessage()).isEqualTo(ResponseCodeEnum.UNAUTHORIZED.protoName());
        assertFalse(exception.shouldRollbackStack());
    }

    @SuppressWarnings({"ThrowableNotThrown"})
    @Test
    void testConstructorWithIllegalParameters() {
        assertThatThrownBy(() -> new WorkflowException(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void trueIsntProblematic() {
        assertDoesNotThrow(() -> WorkflowException.validateTrue(true, MEMO_TOO_LONG));
    }

    @Test
    void falseIsProblem() {
        final var failure =
                assertThrows(WorkflowException.class, () -> WorkflowException.validateTrue(false, MEMO_TOO_LONG));

        assertEquals(MEMO_TOO_LONG, failure.getStatus());
    }

    @Test
    void trueIsProblemFromOtherPerspective() {
        final var failure =
                assertThrows(WorkflowException.class, () -> WorkflowException.validateFalse(true, MEMO_TOO_LONG));

        assertEquals(MEMO_TOO_LONG, failure.getStatus());
    }

    @Test
    void falseIsOkFromOtherPerspective() {
        assertDoesNotThrow(() -> WorkflowException.validateFalse(false, MEMO_TOO_LONG));
    }
}
