// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import com.swirlds.component.framework.transformers.AdvancedTransformation;
import com.swirlds.platform.state.signed.ReservedSignedState;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Manages reservations of a signed state when it needs to be passed to one or more input wires.
 * <p>
 * The contract for managing reservations across vertexes in the wiring is as follows:
 * <ul>
 *     <li>Each vertex, on input, will receive a state reserved for that vertex</li>
 *     <li>The vertex which should either release that state, or return it</li>
 * </ul>
 * The reserver enforces this contract by reserving the state for each input wire, and then releasing the reservation
 * made for the reserver.
 * <p>
 * For each input wire, {@link #transform(ReservedSignedState)} will be called once, reserving the state for that input
 * wire. After a reservation is made for each input wire, {@link #inputCleanup(ReservedSignedState)} will be called once to
 * release the original reservation.
 *
 * @param name the name of the reserver
 */
public record SignedStateReserver(@NonNull String name)
        implements AdvancedTransformation<ReservedSignedState, ReservedSignedState> {

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public ReservedSignedState transform(@NonNull final ReservedSignedState reservedSignedState) {
        return reservedSignedState.getAndReserve(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void inputCleanup(@NonNull final ReservedSignedState reservedSignedState) {
        reservedSignedState.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void outputCleanup(@NonNull final ReservedSignedState reservedSignedState) {
        reservedSignedState.close();
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getTransformerName() {
        return name;
    }

    @NonNull
    @Override
    public String getTransformerInputName() {
        return "state to reserve";
    }
}
