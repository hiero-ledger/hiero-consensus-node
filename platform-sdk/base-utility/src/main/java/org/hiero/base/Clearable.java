// SPDX-License-Identifier: Apache-2.0
package org.hiero.base;

/**
 * Represents a function that clears all internal data and resets the instance to its initial state
 */
@FunctionalInterface
public interface Clearable {
    /**
     * Clears all internal data and resets the instance to its initial state
     */
    void clear();
}
