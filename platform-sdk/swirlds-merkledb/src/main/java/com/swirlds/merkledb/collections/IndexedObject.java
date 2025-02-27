// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.collections;

/**
 * Simple interface for an object that has an index.
 */
public interface IndexedObject {

    /**
     * Gets this object's index; that is, an ordered integer identifying the object.
     *
     * @return this object's index
     */
    int getIndex();
}
