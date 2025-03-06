// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.state;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Describes an object that may be mutable or immutable.
 */
public interface Mutable {

    /**
     * Determines if an object is immutable or not.
     *
     * @return Whether is immutable or not
     */
    boolean isImmutable();

    /**
     * Determines if an object is mutable or not.
     *
     * @return Whether the object is immutable or not
     */
    default boolean isMutable() {
        return !isImmutable();
    }

    /**
     * @throws MutabilityException if {@link #isImmutable()}} returns {@code true}
     */
    default void throwIfImmutable() {
        throwIfImmutable("This operation is not permitted on an immutable object.");
    }

    /**
     * @param errorMessage an error message for the exception
     * @throws MutabilityException if {@link #isImmutable()}} returns {@code true}
     */
    default void throwIfImmutable(@NonNull final String errorMessage) {
        if (isImmutable()) {
            throw new MutabilityException(errorMessage);
        }
    }

    /**
     * @throws MutabilityException if {@link #isMutable()} returns {@code true}
     */
    default void throwIfMutable() {
        throwIfMutable("This operation is not permitted on a mutable object.");
    }

    /**
     * @param errorMessage an error message for the exception
     * @throws MutabilityException if {@link #isMutable()}} returns {@code true}
     */
    default void throwIfMutable(@NonNull final String errorMessage) {
        if (isMutable()) {
            throw new MutabilityException(errorMessage);
        }
    }
}
