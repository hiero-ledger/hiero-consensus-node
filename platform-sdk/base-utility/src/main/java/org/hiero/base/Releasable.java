// SPDX-License-Identifier: Apache-2.0
package org.hiero.base;

import org.hiero.base.exceptions.ReferenceCountException;

/**
 * A Releasable object is an object that expects to be deleted when it is done being used.
 * This paradigm is needed when objects manage resources that are not automatically cleaned
 * by the java garbage collector.
 */
public interface Releasable {

    /**
     * Called when this object is no longer needed.
     *
     * @return true if this call to release() caused the object to become destroyed
     */
    default boolean release() {
        // override if needed
        return false;
    }

    /**
     * Determines if an object has been fully released and garbage collected.
     *
     * @return Whether is has been released or not
     */
    default boolean isDestroyed() {
        return false;
    }

    /**
     * Throws an exception if {@link #isDestroyed()}} returns {@code true}
     *
     * @throws ReferenceCountException
     * 		if this object is destroyed
     */
    default void throwIfDestroyed() {
        throwIfDestroyed("This operation is not permitted on a destroyed object.");
    }

    /**
     * Throws an exception if {@link #isDestroyed()}} returns {@code true}
     *
     * @param errorMessage
     * 		an error message for the exception
     * @throws ReferenceCountException
     * 		if this object is destroyed
     */
    default void throwIfDestroyed(final String errorMessage) {
        if (this.isDestroyed()) {
            throw new ReferenceCountException(errorMessage);
        }
    }
}
