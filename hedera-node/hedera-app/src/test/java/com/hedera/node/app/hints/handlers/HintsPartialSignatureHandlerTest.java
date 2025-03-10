// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.handlers;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.ConcurrentMap;

@ExtendWith(MockitoExtension.class)
class HintsPartialSignatureHandlerTest {
    @Mock
    private HintsContext context;

    @Mock
    private HintsLibrary library;

    @Mock
    private PureChecksContext pureChecksContext;

    @Mock
    private PreHandleContext preHandleContext;

    @Mock
    private HandleContext handleContext;

    HintsPartialSignatureHandler subject;

//    @BeforeEach
//    void setUp() {
//        subject = new HintsPartialSignatureHandler(new ConcurrentMap<Bytes, HintsContext.Signing>() {
//        }, library);
//    }
//
//    @Test
//    void pureChecksDoNothing() {
//        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
//    }
//
//    @Test
//    void nothingElseImplemented() {
//        assertThrows(UnsupportedOperationException.class, () -> subject.preHandle(preHandleContext));
//        assertThrows(UnsupportedOperationException.class, () -> subject.handle(handleContext));
//    }
}
