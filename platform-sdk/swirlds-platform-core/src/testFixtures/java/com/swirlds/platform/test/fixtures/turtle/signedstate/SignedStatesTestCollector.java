// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.signedstate;

import com.swirlds.platform.state.MerkleNodeState;
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
     * @param merkleStates the roots used to clear specific signed states
     */
    void clear(@NonNull final Set<MerkleNodeState> merkleStates);

    /**
     * Get the collected reserved signed states.
     *
     * @return the collected reserved signed states
     */
    Map<Long, ReservedSignedState> getCollectedSignedStates();

    /**
     * Get filtered signed states by specified state roots.
     *
     * @param merkleStates the roots collection to use as a filter
     * @return the filtered signed states
     */
    List<ReservedSignedState> getFilteredSignedStates(@NonNull final Set<MerkleNodeState> merkleStates);
}
