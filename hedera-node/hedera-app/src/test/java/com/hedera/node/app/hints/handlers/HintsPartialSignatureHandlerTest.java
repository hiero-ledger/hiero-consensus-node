/*
 * Copyright (C) 2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.hints.handlers;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.node.app.hints.HintsLibrary;
import com.hedera.node.app.hints.impl.HintsContext;
import com.hedera.node.app.spi.workflows.HandleContext;
import com.hedera.node.app.spi.workflows.PreHandleContext;
import com.hedera.node.app.spi.workflows.PureChecksContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @BeforeEach
    void setUp() {
        subject = new HintsPartialSignatureHandler(context, library);
    }

    @Test
    void pureChecksDoNothing() {
        assertDoesNotThrow(() -> subject.pureChecks(pureChecksContext));
    }

    @Test
    void nothingElseImplemented() {
        assertThrows(UnsupportedOperationException.class, () -> subject.preHandle(preHandleContext));
        assertThrows(UnsupportedOperationException.class, () -> subject.handle(handleContext));
    }
}
