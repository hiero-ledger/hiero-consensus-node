// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.transaction.handling.internal;

import com.swirlds.component.framework.transformers.AdvancedTransformation;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * Manages reservations of a signed state contained in a {@link org.hiero.consensus.state.signed.StateWithHashComplexity} object, when the StateAndRound
 * needs to be reduced to just the state.
 * <p>
 * The contract for managing reservations across vertexes in the wiring is as follows:
 * <ul>
 *     <li>Each vertex, on input, will receive a state reserved for that vertex</li>
 *     <li>The vertex which should either release that state, or return it</li>
 * </ul>
 * The reserver enforces this contract by reserving the state for each input wire, and then releasing the reservation
 * made for the reserver.
 * <p>
 * For each input wire, {@link #transform(org.hiero.consensus.state.signed.StateWithHashComplexity)} will be called once, reserving the state for that input
 * wire. After a reservation is made for each input wire, {@link #inputCleanup(org.hiero.consensus.state.signed.StateWithHashComplexity)} will be called once to
 * release the original reservation.
 *
 * @param name the name of the reserver
 */
public record StateWithHashComplexityToStateReserver(
        @NonNull String name)
        implements AdvancedTransformation<
                org.hiero.consensus.state.signed.StateWithHashComplexity, ReservedSignedState> {

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public ReservedSignedState transform(
            @NonNull final org.hiero.consensus.state.signed.StateWithHashComplexity stateWithHashComplexity) {
        return stateWithHashComplexity.reservedSignedState().getAndReserve(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void inputCleanup(
            @NonNull final org.hiero.consensus.state.signed.StateWithHashComplexity stateWithHashComplexity) {
        stateWithHashComplexity.reservedSignedState().close();
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

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public String getTransformerInputName() {
        return "state with hash complexity to reserve";
    }
}
