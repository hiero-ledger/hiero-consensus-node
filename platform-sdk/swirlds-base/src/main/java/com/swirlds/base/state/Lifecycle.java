// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.state;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * An object with a well-defined start/stop lifecycle.
 */
public interface Lifecycle extends Startable, Stoppable {

    /**
     * Get the current phase of this object.
     *
     * @return the object's current lifecycle phase
     */
    @NonNull
    LifecyclePhase getLifecyclePhase();

    /**
     * Throw an exception if the object is not in the expected phase.
     *
     * @param phase the expected phase
     * @throws LifecycleException if the object is not in the expected phase
     */
    default void throwIfNotInPhase(@NonNull final LifecyclePhase phase) {
        throwIfNotInPhase(phase, "object is in an unexpected phase");
    }

    /**
     * Throw an exception if the object is not in the expected phase.
     *
     * @param phase        the expected phase
     * @param errorMessage a message that provides additional information if an exception is thrown
     * @throws LifecycleException if the object is not in the expected phase
     */
    default void throwIfNotInPhase(@NonNull final LifecyclePhase phase, @Nullable final String errorMessage) {
        Objects.requireNonNull(phase, "phase must not be null");
        final LifecyclePhase currentPhase = getLifecyclePhase();
        if (currentPhase != phase) {
            throw new LifecycleException(
                    errorMessage + ": current phase = " + currentPhase + ", expected phase = " + phase);
        }
    }
}
