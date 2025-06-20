// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.hints.impl;

import static org.junit.jupiter.api.Assertions.*;

import com.hedera.node.app.hints.handlers.CrsPublicationHandler;
import com.hedera.node.app.hints.handlers.HintsHandlers;
import com.hedera.node.app.hints.handlers.HintsKeyPublicationHandler;
import com.hedera.node.app.hints.handlers.HintsPartialSignatureHandler;
import com.hedera.node.app.hints.handlers.HintsPreprocessingVoteHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HintsModuleTest {
    @Mock
    private HintsKeyPublicationHandler keyPublicationHandler;

    @Mock
    private HintsPreprocessingVoteHandler preprocessingVoteHandler;

    @Mock
    private HintsPartialSignatureHandler partialSignatureHandler;

    @Mock
    private CrsPublicationHandler crsPublicationHandler;

    @Test
    void constructsHintsHandlers() {
        assertInstanceOf(
                HintsHandlers.class,
                HintsModule.provideHintsHandlers(
                        keyPublicationHandler,
                        preprocessingVoteHandler,
                        partialSignatureHandler,
                        crsPublicationHandler));
    }
}
