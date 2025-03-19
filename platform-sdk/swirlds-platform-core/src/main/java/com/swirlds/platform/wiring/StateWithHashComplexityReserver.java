// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.wiring;

import com.swirlds.component.framework.transformers.AdvancedTransformation;
import com.swirlds.platform.eventhandling.StateWithHashComplexity;
import com.swirlds.platform.eventhandling.TransactionHandlerResult;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Manages reservations of a signed state contained in a {@link TransactionHandlerResult} object when the
 * TransactionHandlerResult needs to be reduced to the {@link StateWithHashComplexity} object.
 * <p>
 * The contract for managing reservations across vertexes in the wiring is as follows:
 * <ul>
 *     <li>Each vertex, on input, will receive a state reserved for that vertex</li>
 *     <li>The vertex which should either release that state, or return it</li>
 * </ul>
 * The reserver enforces this contract by reserving the state for each input wire, and then releasing the reservation
 * made for the reserver.
 * <p>
 * For each input wire, {@link #transform(TransactionHandlerResult)} will be called once, reserving the state for that input
 * wire. After a reservation is made for each input wire, {@link #inputCleanup(TransactionHandlerResult)} will be called once to
 * release the original reservation.
 *
 * @param name the name of the reserver
 */
public record StateWithHashComplexityReserver(@NonNull String name)
        implements AdvancedTransformation<TransactionHandlerResult, StateWithHashComplexity> {

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public StateWithHashComplexity transform(@NonNull final TransactionHandlerResult transactionHandlerResult) {
        return transactionHandlerResult.stateWithHashComplexity().makeAdditionalReservation(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void inputCleanup(@NonNull final TransactionHandlerResult transactionHandlerResult) {
        transactionHandlerResult.stateWithHashComplexity().reservedSignedState().close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void outputCleanup(@NonNull final StateWithHashComplexity stateWithHashComplexity) {
        stateWithHashComplexity.reservedSignedState().close();
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
        return "non null state with hash complexity";
    }
}
