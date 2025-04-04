// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.signedstate;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A test component collecting reserved signed states.
 */
public interface SignedStatesTestCollector {

    /**
     * Intercept signed state because either a full set of signatures has been collected or the time
     * to collect the signatures has expired.
     *
     * @param signedState the signed state to add in a collection
     */
    void interceptReservedSignedState(@NonNull final ReservedSignedState signedState);

    /**
     * Clear the internal state of this collector.
     *
     * @param roundNumbers the round numbers to use to clear specific signed states
     */
    void clear(@NonNull final Set<Long> roundNumbers);

    /**
     * Get the collected reserved signed states.
     *
     * @return the collected reserved signed states
     */
    @NonNull
    Map<Long, ReservedSignedState> getCollectedSignedStates();

    /**
     * Get filtered signed states by specified state roots.
     *
     * @param roundNumbers the round numbers to use as a filter
     * @return the filtered signed states
     */
    @NonNull
    List<ReservedSignedState> getFilteredSignedStates(@NonNull final Set<Long> roundNumbers);
}
