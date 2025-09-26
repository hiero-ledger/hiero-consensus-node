// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * The {@link SignedStateSentinel} is responsible for observing the lifespans of signed states, and taking action if a
 * state suspected of a memory leak is observed.
 */
public interface SignedStateSentinel {
    /**
     * Check the maximum age gap of signed states, and take action if a really old state is observed.
     *
     * @param now the current time
     */
    void checkSignedStates(@NonNull final Instant now);
}
