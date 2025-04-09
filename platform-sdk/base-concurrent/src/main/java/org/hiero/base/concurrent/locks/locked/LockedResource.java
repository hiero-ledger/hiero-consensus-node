// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.concurrent.locks.locked;

import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Provides access to the resource that has been locked
 *
 * @param <T>
 * 		the type of resource
 */
public interface LockedResource<T> extends Locked {
    /**
     * @return the locked resource, may be null
     */
    @Nullable
    T getResource();

    /**
     * Sets the resource
     *
     * @param resource
     * 		the object to set
     */
    void setResource(@Nullable T resource);
}
