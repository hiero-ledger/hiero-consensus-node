// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.time;

import com.swirlds.base.time.internal.OSTime;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.time.InstantSource;

/**
 * An API for getting the time. All platform code should utilize this API instead of the raw standard
 * java time APIs. This makes it much easier to simulate time in test environments.
 */
public interface Time extends InstantSource {

    /**
     * A method that returns the time in nanoseconds. May not start at the epoch.
     * Equivalent to {@link System#nanoTime()}.
     *
     * @return the current relative time in nanoseconds
     */
    long nanoTime();

    /**
     * A method that returns the current time in milliseconds since the epoch. Equivalent to
     * {@link System#currentTimeMillis()}.
     *
     * @return the current time since the epoch in milliseconds
     */
    long currentTimeMillis();

    /**
     * Returns the current time, relative to the epoch. Equivalent to {@link Instant#now()}.
     *
     * @return the current time relative to the epoch
     */
    @NonNull
    Instant now();

    /**
     * Returns a {@link Time} instance.
     *
     * @return a {@link Time} instance
     */
    @NonNull
    static Time getCurrent() {
        return OSTime.getInstance();
    }

    @Override
    default Instant instant() {
        return now();
    }
}
