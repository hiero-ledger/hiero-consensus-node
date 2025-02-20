// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hapi.utils.sysfiles.validation;

import static com.hederahashgraph.api.proto.java.ResponseCodeEnum.NODE_CAPACITY_NOT_SUFFICIENT_FOR_OPERATION;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ErrorCodeUtilsTest {
    @Test
    void usesTplForExceptionMsg() {
        final var details = "YIKES!";
        final var expectedMsg = "NODE_CAPACITY_NOT_SUFFICIENT_FOR_OPERATION :: " + details;

        final var actualMsg = ErrorCodeUtils.exceptionMsgFor(NODE_CAPACITY_NOT_SUFFICIENT_FOR_OPERATION, details);

        assertEquals(expectedMsg, actualMsg);
    }

    @Test
    void extractsErrorCodeFromMsg() {
        final var msg = "NODE_CAPACITY_NOT_SUFFICIENT_FOR_OPERATION :: YIKES!";

        assertEquals(Optional.of(NODE_CAPACITY_NOT_SUFFICIENT_FOR_OPERATION), ErrorCodeUtils.errorFrom(msg));
    }

    @Test
    void returnsEmptyOptionalIfNoErrorCode() {
        final var msg = "YIKES!";

        assertEquals(Optional.empty(), ErrorCodeUtils.errorFrom(msg));
    }
}
