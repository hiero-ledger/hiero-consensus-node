// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common;

import com.swirlds.common.exceptions.ReferenceCountException;

/**
 * <p>
 * An object that can be reserved and released. Number of reservations and releases are tracked via a reference count.
 * </p>
 *
 * <p>
 * An important paradigm to understand with this interface and with classes that implement this interface are
 * "implicit reservations" and "explicit reservations".
 * </p>
 *
 * <p>
 * When an object is initially constructed, it is considered to have an implicit reservation. Even though
 * its reservation count is 0, we don't want to garbage collect it -- as presumably the caller of the constructor
 * still needs the object. The reservation count of an object with an implicit reservation is 0. Calling
 * {@link #release()} on an object with an implicit reservation will cause that object to be destroyed.
 * </p>
 *
 * <p>
 * When an object with an implicit reservation has {@link #reserve()} called on it the first time, that reservation
 * becomes explicit and has a reservation count of 1. After this point in time, the reservation remains explicit
 * until the object is eventually destroyed. It is impossible for an object with an explicit reservation to
 * return to having an implicit reservation. If at any time after the object obtains an explicit reservation it
 * has {@link #release()} called enough times to reduce its reference count to 0, then that object is destroyed
 * and has its reference count set to -1.
 * </p>
 */
public interface Reservable extends Releasable {

    /**
     * The reference count of an object with an implicit reference.
     */
    int IMPLICIT_REFERENCE_COUNT = 0;

    /**
     * The reference count of an object with an explicit reference.
     */
    int DESTROYED_REFERENCE_COUNT = -1;

    /**
     * Acquire a reservation on this object. Increments the reference count by 1.
     *
     * @throws ReferenceCountException
     * 		if this object has been fully released and destroyed
     */
    void reserve();

    /**
     * Attempts to acquire a reservation on this object. If the object is destroyed, the reservation attempt will fail.
     *
     * @return true if a reservation was acquired.
     */
    boolean tryReserve();

    /**
     * <p>
     * Release a reservation on an object. Decrements the reference count by 1. If this method releases
     * the last reservation, then this object should be destroyed.
     * </p>
     *
     * <p>
     * Should be called exactly once for each time {@link #reserve()} is called. The exception to this rule is
     * if this object only has an implicit reference (i.e. {@link #getReservationCount()} returns
     * {@link #IMPLICIT_REFERENCE_COUNT}). An object has an implicit reference immediately after it is constructed
     * but before {@link #reserve()} has been called the first time. If called with an implicit reference, this
     * object will be destroyed.
     * </p>
     *
     * @return true if this call to release() caused the object to become destroyed
     * @throws ReferenceCountException
     * 		this object has already been fully released and destroyed
     */
    @Override
    boolean release();

    /**
     * Returns true if this object has had all of its reservations released, or if it has had its implicit reference
     * released.
     *
     * @return if this object has been fully released
     */
    @Override
    boolean isDestroyed();

    /**
     * Get the total number of times {@link #reserve()} has been called minus the number of times {@link #release()}
     * has been called. Will return {@link #IMPLICIT_REFERENCE_COUNT} if {@link #reserve()} has never been called,
     * or {@link #DESTROYED_REFERENCE_COUNT} if this object has been fully released.
     *
     * @return
     */
    int getReservationCount();
}
