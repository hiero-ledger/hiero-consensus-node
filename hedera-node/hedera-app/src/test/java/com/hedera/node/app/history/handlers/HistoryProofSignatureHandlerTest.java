// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.handlers;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HistoryProofSignatureHandlerTest {
    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private HandleContext context;

    @Mock
    private PureChecksContext pureChecksContext;

    private HistoryProofSignatureHandler subject;

    @BeforeEach
    void setUp() {
        subject = new HistoryProofSignatureHandler();
    }

    @Test
    void pureChecksAndPreHandleAndHandleDoNothing() {
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
        assertDoesNotThrow(() -> subject.preHandle(preHandleContext));
        assertDoesNotThrow(() -> subject.handle(context));
    }
}
