/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
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

package com.swirlds.platform.state.signed;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.internal.ConsensusRound;
import com.swirlds.platform.state.hasher.DefaultStateHasher;
import com.swirlds.platform.state.hasher.StateHasher;
import com.swirlds.platform.wiring.components.StateAndRound;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultStateHasher}
 */
public class DefaultStateHasherTests {
    @Test
    @DisplayName("Normal operation")
    void normalOperation() {
        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();

        // create the hasher
        final StateHasher hasher = new DefaultStateHasher(platformContext);

        // mock a state
        final SignedState signedState = mock(SignedState.class);
        final ReservedSignedState reservedSignedState = mock(ReservedSignedState.class);
        when(reservedSignedState.get()).thenReturn(signedState);

        final StateAndRound stateAndRound =
                new StateAndRound(reservedSignedState, mock(ConsensusRound.class), mock(ConcurrentLinkedQueue.class));

        // do the test
        final StateAndRound result = hasher.hashState(stateAndRound);
        assertNotEquals(null, result, "The hasher should return a new StateAndRound");
    }
}
