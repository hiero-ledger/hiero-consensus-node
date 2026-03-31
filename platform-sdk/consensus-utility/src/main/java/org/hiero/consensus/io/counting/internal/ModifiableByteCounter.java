// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.io.counting.internal;

import org.hiero.consensus.io.counting.ByteCounter;

/**
 * An interface that allows to add to the {@link ByteCounter}.
 */
public interface ModifiableByteCounter extends ByteCounter {
    /**
     * Adds the specified value to the count
     *
     * @param value the value to be added
     */
    void addToCount(long value);
}
