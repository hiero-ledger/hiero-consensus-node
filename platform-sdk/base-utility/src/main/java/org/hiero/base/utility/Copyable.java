// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility;

/**
 * An object that can be copied.
 */
@FunctionalInterface
public interface Copyable {

    /**
     * Get a copy of the object. The returned object type must be the same type as the original.
     *
     * @return a copy of the object
     */
    <T extends Copyable> T copy();
}
