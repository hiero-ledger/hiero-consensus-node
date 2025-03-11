// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.test.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.service.util.impl.handlers.AtomicBatchHandler;
import com.hedera.node.app.service.util.impl.handlers.UtilHandlers;
import com.hedera.node.app.service.util.impl.handlers.UtilPrngHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UtilHandlersTest {
    private UtilPrngHandler prngHandler;
    private AtomicBatchHandler atomicBatchHandler;

    private UtilHandlers utilHandlers;

    @BeforeEach
    public void setUp() {
        prngHandler = mock(UtilPrngHandler.class);
        atomicBatchHandler = mock(AtomicBatchHandler.class);
        utilHandlers = new UtilHandlers(prngHandler, atomicBatchHandler);
    }

    @Test
    void prngHandlerReturnsCorrectInstance() {
        assertEquals(prngHandler, utilHandlers.prngHandler(), "prngHandler does not return correct instance");
        assertEquals(
                atomicBatchHandler,
                utilHandlers.atomicBatchHandler(),
                "atomicBatchHandler does not return correct instance");
    }
}
