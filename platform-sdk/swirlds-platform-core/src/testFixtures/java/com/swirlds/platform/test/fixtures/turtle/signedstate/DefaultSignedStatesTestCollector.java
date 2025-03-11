// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.signedstate;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;

/**
 * A container for collecting reserved signed states using List.
 */
public class DefaultSignedStatesTestCollector implements SignedStatesTestCollector {

    final List<ReservedSignedState> collectedSignedStates = new ArrayList<>();

    /**
     * {@inheritDoc}
     */
    @Override
    public void interceptReservedSignedState(@NonNull final ReservedSignedState signedState) {
        try (signedState) {
            collectedSignedStates.add(signedState);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        collectedSignedStates.clear();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<ReservedSignedState> getCollectedSignedStates() {
        return collectedSignedStates;
    }
}
