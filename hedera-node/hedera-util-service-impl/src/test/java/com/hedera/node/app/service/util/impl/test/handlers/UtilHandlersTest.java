// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.util.impl.test.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.hedera.node.app.service.util.impl.handlers.UtilHandlers;
import com.hedera.node.app.service.util.impl.handlers.UtilPrngHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class UtilHandlersTest {
    private UtilPrngHandler prngHandler;

    private UtilHandlers utilHandlers;

    @BeforeEach
    public void setUp() {
        prngHandler = mock(UtilPrngHandler.class);
        utilHandlers = new UtilHandlers(prngHandler);
    }

    @Test
    void prngHandlerReturnsCorrectInstance() {
        assertEquals(prngHandler, utilHandlers.prngHandler(), "prngHandler does not return correct instance");
    }
}
