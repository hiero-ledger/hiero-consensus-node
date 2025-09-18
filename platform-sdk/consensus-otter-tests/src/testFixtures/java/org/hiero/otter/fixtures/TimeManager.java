// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.hiero.otter.fixtures.util.TimeoutException;

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
     */
    void waitFor(@NonNull Duration waitTime);

    /**
     * Wait for a condition to become {@code true} within a specified time.
     *
     * @param condition the condition to wait for, which should return {@code true} when the condition is met
     * @param waitTime the maximum duration to wait for the condition to become true
     * @throws TimeoutException if the condition does not become true within the specified time
     */
    void waitForCondition(@NonNull final BooleanSupplier condition, @NonNull final Duration waitTime)
            throws TimeoutException;

    /**
     * Wait for a condition to become {@code true} within a specified time, with a custom timeout message.
     *
     * @param condition the condition to wait for, which should return {@code true} when the condition is met
     * @param waitTime the maximum duration to wait for the condition to become true
     * @param message the message to include in the exception if a timeout occurs
     * @throws TimeoutException if the condition does not become true within the specified time
     * @see #waitForCondition(BooleanSupplier, Duration)
     */
    void waitForCondition(
            @NonNull final BooleanSupplier condition, @NonNull final Duration waitTime, @NonNull final String message)
            throws TimeoutException;

    /**
     * Returns the current time.
     *
     * @return the current time
     */
    @NonNull
    Instant now();
}
