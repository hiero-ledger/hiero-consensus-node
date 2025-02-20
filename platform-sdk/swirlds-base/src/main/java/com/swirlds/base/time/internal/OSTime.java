// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.time.internal;

import com.swirlds.base.time.Time;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;

/**
 * An implementation of {@link Time} that will return the true wall clock time (according to the OS).
 */
public final class OSTime implements Time {

    private static final class InstanceHolder {
        private static final Time INSTANCE = new OSTime();
    }

    private OSTime() {}

    /**
     * Get a static instance of a standard time implementation.
     */
    @NonNull
    public static Time getInstance() {
        return InstanceHolder.INSTANCE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long nanoTime() {
        return System.nanoTime();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Instant now() {
        return Instant.now();
    }
}
