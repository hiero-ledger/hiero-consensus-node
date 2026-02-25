// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.utility;

import com.swirlds.common.Reservable;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntUnaryOperator;
import org.hiero.base.Releasable;
import org.hiero.base.exceptions.ReferenceCountException;

/**
 * An implementation of {@link Releasable Releasable}.
 */
public abstract class AbstractReservable implements Reservable {

    private final AtomicInteger reservationCount = new AtomicInteger(0);

    /**
     * This lambda method is used to increment the reservation count when {@link #reserve()} is called.
     */
    private static final IntUnaryOperator RESERVE = current -> {
        if (current == DESTROYED_REFERENCE_COUNT) {
            throw new ReferenceCountException("can not reserve node that has already been destroyed");
        } else {
            return current + 1;
        }
    };

    /**
     * This lambda method is used to try to increment the reservation count when {@link #tryReserve()} is called.
     */
    private static final IntUnaryOperator TRY_RESERVE = current -> {
        if (current == DESTROYED_REFERENCE_COUNT) {
            return current;
        } else {
            return current + 1;
        }
    };

    /**
     * This lambda method is used to decrement the reservation count when {@link #release()} is called.
     */
    private static final IntUnaryOperator RELEASE = (final int current) -> {
        if (current == DESTROYED_REFERENCE_COUNT) {
            throw new ReferenceCountException("can not release node that has already been destroyed");
        } else if (current == IMPLICIT_REFERENCE_COUNT || current == 1) {
            return DESTROYED_REFERENCE_COUNT;
        } else {
            return current - 1;
        }
    };

    /**
     * {@inheritDoc}
     */
    @Override
    public void reserve() {
        try {
            reservationCount.updateAndGet(RESERVE);
        } catch (final ReferenceCountException e) {
            onReferenceCountException();
            throw e;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean tryReserve() {
        return reservationCount.updateAndGet(TRY_RESERVE) != DESTROYED_REFERENCE_COUNT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean release() {
        final int newReservationCount;
        try {
            newReservationCount = reservationCount.updateAndGet(RELEASE);
        } catch (final ReferenceCountException e) {
            onReferenceCountException();
            throw e;
        }
        if (newReservationCount == DESTROYED_REFERENCE_COUNT) {
            onDestroy();
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDestroyed() {
        return reservationCount.get() == DESTROYED_REFERENCE_COUNT;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getReservationCount() {
        return reservationCount.get();
    }

    /**
     * This method is executed when this object becomes fully released.
     */
    protected void onDestroy() {
        // override this method if needed
    }

    /**
     * This method is executed if there is a {@link ReferenceCountException} when calling {@link #reserve()}. It might
     * be helpful to do extra logging in certain cases here.
     */
    protected void onReferenceCountException() {
        // override this method if needed
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void throwIfDestroyed(@NonNull final String errorMessage) {
        onReferenceCountException();
        Reservable.super.throwIfDestroyed(errorMessage);
    }
}
