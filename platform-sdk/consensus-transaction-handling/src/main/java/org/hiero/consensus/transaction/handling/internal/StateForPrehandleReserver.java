// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.transaction.handling.internal;

import com.swirlds.component.framework.transformers.AdvancedTransformation;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.state.signed.ReservedSignedState;

/**
 * Manages the reservation on the state used for prehandle when a {@link TransactionHandlerResult} is transformed into
 * a {@link ReservedSignedState}.
 *
 * @param name the name of the transformer
 */
public record StateForPrehandleReserver(@NonNull String name)
        implements AdvancedTransformation<TransactionHandlerResult, ReservedSignedState> {

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public ReservedSignedState transform(@NonNull final TransactionHandlerResult transactionHandlerResult) {
        return transactionHandlerResult.stateForPrehandle().getAndReserve(name);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void inputCleanup(@NonNull final TransactionHandlerResult transactionHandlerResult) {
        transactionHandlerResult.stateForPrehandle().close();
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
        return "transaction handler result with state for prehandle";
    }
}
