// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.lifecycle;

import com.swirlds.state.State;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Implementations of this class are responsible for managing the lifecycle of a state object.
 */
public interface StateLifecycleManager {
    /**
     * Sets the initial State for the platform. This method should only be called once.
     * It will through an IllegalStateException if called more than once.
     * Important: the passed state becomes immutable after this method is called.
     *
     * @param state the initial state. It must be mutable and not destroyed.
     */
    void setInitialState(@NonNull final State state);

    /**
     * Overwrites the existing state with the new state.
     * Important: the passed state becomes immutable after this method is called.
     * Unlike {@link #setInitialState(State)}, this method can be called multiple times and the idea of this method
     * is to replace the existing mutable state with the new state.
     *
     * @param state the new state, It must be mutable and not destroyed.
     */
    void overwriteExistingState(@NonNull final State state);

    /**
     * Get the mutable state. The implementation makes sure that this method can't be executed simultaneously
     * with {@link #copyMutableState()}. It can be achieved by using a lock or any other synchronization mechanism.
     */
    State getMutableState();

    /**
     * Creates a mutable copy of the state. The previous mutable state becomes immutable,
     * replacing the latest immutable state.
     *
     * @return an instance of the state that just became immutable, that is, the previous mutable state.
     */
    State copyMutableState();
}
