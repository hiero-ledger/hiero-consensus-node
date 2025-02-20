// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.turtle.signedstate;

import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.List;

/**
 * A test component collecting state signs produced by the {@link com.swirlds.platform.state.signed.StateSignatureCollector}
 */
public interface SignedStateHolder {

    /**
     * Intercept the signed states produced by the StateSignatureCollector and adds them to a collection.
     *
     * @param signedStates the signed state coming from the StateSignatureCollector
     */
    void interceptSignedStates(final List<ReservedSignedState> signedStates);

    /**
     * Clear the internal state of this collector.
     *
     * @param ignored ignored trigger object
     */
    void clear(@NonNull final Object ignored);
}
