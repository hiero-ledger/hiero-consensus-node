// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.io.extendable.extensions.internal;

/**
 * An interface for a counter
 */
public interface Counter {

    /**
     * Resets the count to 0
     */
    void resetCount();

    /**
     * get the current count
     *
     * @return the current count
     */
    long getCount();

    /**
     * Returns the current count and resets it to 0
     *
     * @return the count before the reset
     */
    long getAndResetCount();

    /**
     * Adds the specified value to the count
     *
     * @param value
     * 		the value to be added
     * @return the new count
     */
    long addToCount(long value);
}
