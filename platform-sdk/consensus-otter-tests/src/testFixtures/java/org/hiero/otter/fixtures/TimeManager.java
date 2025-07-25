// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * Interface for managing time in Otter tests.
 *
 * <p>This interface provides methods to wait for a specified duration or other events. Depending on the environment,
 * the implementation may use real time or simulated time.
 */
public interface TimeManager {

    /**
     * Wait for a specified duration.
     *
     * @param waitTime the duration to wait
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    void waitFor(@NonNull Duration waitTime) throws InterruptedException;

    /**
     * Wait for a condition to become {@code true} within a specified time.
     *
     * @param condition the condition to wait for, which should return {@code true} when the condition is met
     * @param waitTime the maximum duration to wait for the condition to become true
     * @return {@code true} if the condition became true within the specified time, {@code false} otherwise
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    boolean waitForCondition(@NonNull final BooleanSupplier condition, @NonNull final Duration waitTime)
            throws InterruptedException;

    /**
     * Returns the current time.
     *
     * @return the current time
     */
    @NonNull
    Instant now();
}
