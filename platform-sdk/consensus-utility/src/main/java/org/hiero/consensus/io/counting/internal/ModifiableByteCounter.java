// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.io.counting.internal;

import org.hiero.consensus.io.counting.ByteCounter;

/**
 * An abstract class that implements the ByteCounter interface and provides a method for adding to the count.
 */
public interface ModifiableByteCounter extends ByteCounter {
    /**
     * Adds the specified value to the count
     *
     * @param value the value to be added
     * @return the new count
     */
    abstract long addToCount(long value);
}
