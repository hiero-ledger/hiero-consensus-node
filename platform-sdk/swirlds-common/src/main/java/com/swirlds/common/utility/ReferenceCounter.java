// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import com.swirlds.common.Reservable;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Objects;

/**
 * A thread safe object that holds a reference count for an object, and then calls a lambda when it is time to destroy
 * the object.
 */
public class ReferenceCounter extends AbstractReservable {

    private final Runnable onDestroy;
    private final Runnable onReferenceCountException;

    /**
     * Create a new reference counter.
     *
     * @param onDestroy a method that is called when the object is destroyed
     */
    public ReferenceCounter(final Runnable onDestroy) {
        this(onDestroy, null);
    }

    /**
     * Create a new reference counter.
     *
     * @param onDestroy                 a method that is called when the object is destroyed
     * @param onReferenceCountException a method that is called when the object is destroyed, ignored if null
     */
    public ReferenceCounter(@NonNull final Runnable onDestroy, @Nullable final Runnable onReferenceCountException) {
        this.onDestroy = Objects.requireNonNull(onDestroy, "onDestroy");
        this.onReferenceCountException = onReferenceCountException;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onDestroy() {
        onDestroy.run();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void onReferenceCountException() {
        if (onReferenceCountException != null) {
            onReferenceCountException.run();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString() {
        final int reservationCount = getReservationCount();
        if (reservationCount == Reservable.DESTROYED_REFERENCE_COUNT) {
            return "destroyed(" + Reservable.DESTROYED_REFERENCE_COUNT + ")";
        } else if (reservationCount == Reservable.IMPLICIT_REFERENCE_COUNT) {
            return "implicit(" + Reservable.IMPLICIT_REFERENCE_COUNT + ")";
        } else {
            return "explicit(" + reservationCount + ")";
        }
    }
}
