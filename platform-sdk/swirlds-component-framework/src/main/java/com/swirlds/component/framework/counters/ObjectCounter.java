// SPDX-License-Identifier: Apache-2.0
package com.swirlds.component.framework.counters;

/**
 * A class that counts the number of objects in various parts of the pipeline.
 */
public abstract class ObjectCounter {

    /**
     * The value returned by {@link #getCount()} if this object counter does not support counting.
     */
    public static final long COUNT_UNDEFINED = -1L;

    /**
     * Signal that an object is entering the part of the system that this object is being used to monitor.
     */
    public abstract void onRamp(long delta);

    public final void onRamp() {
        onRamp(1L);
    }

    /**
     * Signal that an object is entering the part of the system that this object is being used to monitor. Object is not
     * "on ramped" if it is not immediately possible to do so without violating capacity constraints.
     *
     * @return true if there was available capacity to on ramp the object, false otherwise
     */
    public abstract boolean attemptOnRamp(long delta);

    public final boolean attemptOnRamp() {
        return attemptOnRamp(1L);
    }

    /**
     * Signal that an object is entering the part of the system that this object is being used to monitor. If there is
     * not enough capacity to on ramp the object, on ramp it anyway and ignore all capacity restrictions.
     */
    public abstract void forceOnRamp(long delta);

    public final void forceOnRamp() {
        forceOnRamp(1L);
    }

    /**
     * Signal that an object is leaving the part of the system that this object is being used to monitor.
     */
    public abstract void offRamp(long delta);

    public final void offRamp() {
        offRamp(1L);
    }

    /**
     * Get the number of objects in the part of the system that this object is being used to monitor. If this object
     * counter does not support counting, then {@link #COUNT_UNDEFINED} is returned.
     */
    public abstract long getCount();

    /**
     * Blocks until the number of objects off-ramped is equal to the number of objects on-ramped. Does not prevent new
     * objects from being on-ramped. If new objects are continuously on-ramped, it is possible that this method may
     * block indefinitely.
     */
    public abstract void waitUntilEmpty();
}
