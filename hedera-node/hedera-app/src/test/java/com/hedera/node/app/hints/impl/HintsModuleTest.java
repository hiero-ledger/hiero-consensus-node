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
