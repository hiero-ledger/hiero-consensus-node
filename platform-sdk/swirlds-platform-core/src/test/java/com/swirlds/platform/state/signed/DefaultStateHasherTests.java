// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.eventhandling.StateWithHashComplexity;
import com.swirlds.platform.state.hasher.DefaultStateHasher;
import com.swirlds.platform.state.hasher.StateHasher;
import com.swirlds.state.merkle.VirtualMapState;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;
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
        final VirtualMapState virtualMapState = mock(VirtualMapState.class);
        final ReservedSignedState reservedSignedState = mock(ReservedSignedState.class);
        when(reservedSignedState.get()).thenReturn(signedState);
        when(signedState.getState()).thenReturn(virtualMapState);

        // do the test
        final ReservedSignedState result = hasher.hashState(new StateWithHashComplexity(reservedSignedState, 1));
        assertNotEquals(null, result, "The hasher should return a new StateAndRound");
    }
}
