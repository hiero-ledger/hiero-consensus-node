// SPDX-License-Identifier: Apache-2.0
package com.swirlds.virtualmap;

import org.hiero.consensus.model.io.SelfSerializable;

/**
 * A virtual key, specifically for use with the Virtual FCMap {@code VirtualMap}. The indexes
 * used for looking up values are all stored on disk in order to support virtualization to
 * massive numbers of entities. This requires that any key used with the {@code VirtualMap}
 * needs to be serializable.
 *
 * <p>Keys must implement {@link Comparable}.
 */
public interface VirtualKey extends SelfSerializable {

    /**
     * This needs to be a very good quality hash code with even spread, or it will be very inefficient when used in
     * HalfDiskHashMap.
     *
     * @return Strong well distributed hash code
     */
    @Override
    int hashCode();
}
