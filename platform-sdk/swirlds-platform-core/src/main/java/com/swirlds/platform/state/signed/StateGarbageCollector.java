// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * This component is responsible for the deletion of signed states. In case signed state deletion is expensive, we never
 * want to delete a signed state on the last thread that releases it.
 */
public interface StateGarbageCollector {

    /**
     * Register a signed state with the garbage collector. The garbage collector will eventually delete the state when
     * it is no longer needed.
     *
     * @param reservedSignedState the state to register
     */
    void registerState(@NonNull ReservedSignedState reservedSignedState);

    /**
     * This method is called periodically to give the signed state manager a chance to delete states.
     */
    void heartbeat();
}
