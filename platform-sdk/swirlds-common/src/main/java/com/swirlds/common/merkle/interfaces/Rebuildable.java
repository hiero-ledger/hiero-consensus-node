// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.merkle.interfaces;

/**
 * An object that contains rebuildable data structures (sometimes referred to as "metadata").
 */
public interface Rebuildable {

    /**
     * Rebuild all metadata structures.
     */
    void rebuild();
}
