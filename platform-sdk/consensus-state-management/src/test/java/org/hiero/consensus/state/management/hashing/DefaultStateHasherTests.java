// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.state.management.hashing;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.state.merkle.VirtualMapState;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.state.signed.SignedState;
import org.hiero.consensus.state.signed.StateWithHashComplexity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultStateHasher}
 */
public class DefaultStateHasherTests {
    @Test
    @DisplayName("Normal operation")
    void normalOperation() {
        // create the hasher
        final StateHasher hasher = new DefaultStateHasher(new NoOpMetrics());

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
