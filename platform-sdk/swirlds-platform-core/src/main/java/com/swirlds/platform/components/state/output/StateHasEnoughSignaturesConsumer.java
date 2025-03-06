// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components.state.output;

import com.swirlds.platform.state.signed.SignedState;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * An event when a signed state gathers enough signatures to be considered complete.
 * The signed state in this event may be older than the latest complete
 * signed state but gathers enough signatures to be considered complete. If this state is also the latest
 * complete signed state, then both events are created.
 */
@FunctionalInterface
public interface StateHasEnoughSignaturesConsumer {

    /**
     * A signed state has just collected enough signatures to be complete.
     *
     * @param signedState the signed state
     */
    void stateHasEnoughSignatures(@NonNull SignedState signedState);
}
