// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.components.state.output;

import com.swirlds.platform.state.signed.ReservedSignedState;
import com.swirlds.platform.state.signed.SignedState;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Invoked when a signed state fails to collect sufficient signatures before being ejected from memory.
 * <p>
 * The state within the {@link ReservedSignedState} holds a reservation. The wiring layer must release the
 * {@link ReservedSignedState} after all consumers have completed.
 */
@FunctionalInterface
public interface StateLacksSignaturesConsumer {

    /**
     * A signed state is about to be ejected from memory and has not collected enough signatures to be complete.
     *
     * @param signedState the signed state
     */
    void stateLacksSignatures(@NonNull SignedState signedState);
}
