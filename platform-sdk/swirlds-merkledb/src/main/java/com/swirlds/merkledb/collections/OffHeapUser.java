// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

/**
 * This interface is implemented by classes that use off-heap memory.
 */
public interface OffHeapUser {
    /**
     * @return the number of bytes of off-heap memory consumed by the object of this class
     */
    long getOffHeapConsumption();
}
