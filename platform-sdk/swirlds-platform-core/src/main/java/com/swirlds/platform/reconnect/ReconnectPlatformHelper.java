// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.reconnect;

import com.swirlds.platform.state.signed.SignedState;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Hides complexities of preparing the platform for impending reconnect process and way to update platform
 * with new state after it is retrieved
 */
public interface ReconnectPlatformHelper {
    /**
     * Performs necessary operations before a reconnect such as stopping threads, clearing queues, etc.
     */
    void prepareForReconnect();

    /**
     * Used to load the state received from the sender.
     *
     * @param signedState the signed state that was received from the sender
     * @return true if the state was successfully loaded; otherwise false
     */
    boolean loadSignedState(@NonNull SignedState signedState);
}
