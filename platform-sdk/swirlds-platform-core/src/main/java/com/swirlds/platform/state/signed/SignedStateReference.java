// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.state.signed;

import static com.swirlds.platform.state.signed.ReservedSignedState.createNullReservation;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import org.hiero.base.concurrent.locks.AutoClosableLock;
import org.hiero.base.concurrent.locks.Locks;
import org.hiero.base.concurrent.locks.locked.Locked;

/**
 * Similar to an {@link java.util.concurrent.atomic.AtomicReference AtomicReference&lt;SignedState&gt;}, but with the
 * additional functionality of holding a reservation on the contained state.
 */
public class SignedStateReference {

    private ReservedSignedState reservedSignedState = createNullReservation();
    private final AutoClosableLock lock = Locks.createAutoLock();

    /**
     * Create a new signed state reference with a null initial state.
     */
    public SignedStateReference() {}

    /**
     * Create a new signed state reference with an initial state.
     *
     * @param signedState a signed state
     * @param reason      a short description of why this SignedState is being reserved. Each location where a
     *                    SignedState is reserved should attempt to use a unique reason, as this makes debugging
     *                    reservation bugs easier.
     */
    public SignedStateReference(@NonNull final SignedState signedState, @NonNull final String reason) {
        set(signedState, reason);
    }

    /**
     * Set a signed state, replacing the existing state.
     *
     * @param signedState a signed state, may be null
     * @param reason      a short description of why this SignedState is being reserved. Each location where a
     *                    SignedState is reserved should attempt to use a unique reason, as this makes debugging
     *                    reservation bugs easier.
     */
    public void set(@Nullable final SignedState signedState, @NonNull final String reason) {
        try (final Locked l = lock.lock()) {
            if (signedState == reservedSignedState.getNullable()) {
                // Same object, no action required
                return;
            }

            reservedSignedState.close();
            reservedSignedState = signedState == null
                    ? createNullReservation()
                    : ReservedSignedState.createAndReserve(signedState, reason);
        }
    }

    /**
     * Set this reference to null.
     */
    public void clear() {
        set(null, "");
    }

    /**
     * Check if the current value referenced by this object is null.
     *
     * @return true if the referenced state is null
     */
    public boolean isNull() {
        try (final Locked l = lock.lock()) {
            return reservedSignedState.isNull();
        }
    }

    /**
     * Get the round of the signed state contained by this reference.
     *
     * @return the signed state's round, or -1 if this object references a null object
     */
    public long getRound() {
        try (final Locked l = lock.lock()) {
            return reservedSignedState.isNull() ? -1 : reservedSignedState.get().getRound();
        }
    }

    /**
     * Get the signed state and take a reservation on it.
     *
     * @param reason a short description of why this SignedState is being reserved. Each location where a SignedState is
     *               reserved should attempt to use a unique reason, as this makes debugging reservation bugs easier.
     * @return an autocloseable wrapper with the state, when closed the state is released
     */
    public ReservedSignedState getAndReserve(@NonNull final String reason) {
        try (final Locked l = lock.lock()) {
            return reservedSignedState.getAndReserve(reason);
        }
    }
}
