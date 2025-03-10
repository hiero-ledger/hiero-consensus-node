// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.signedstate;

import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.StateSignatureCollector;
import java.util.List;

/**
 * A test component collecting state signs produced by the {@link StateSignatureCollector}
 */
public interface SignedStatesTestCollector {

    /**
     * Intercept signed state because either a full set of signatures has been collected or the time
     * to collect the signatures has expired.
     *
     * @param signedState the signed state to add in a collection
     */
    void interceptReservedSignedState(final ReservedSignedState signedState);

    /**
     * Clear the internal state of this collector.
     *
     */
    void clear();

    /**
     * Get the collected reserved signed states.
     *
     * @return the collected reserved signed states
     */
    List<ReservedSignedState> getCollectedSignedStates();
}
