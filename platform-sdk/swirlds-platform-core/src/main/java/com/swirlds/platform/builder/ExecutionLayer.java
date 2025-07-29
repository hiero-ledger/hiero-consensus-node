// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.EventTransactionSupplier;
import org.hiero.consensus.model.transaction.SignatureTransactionCheck;

/**
 * An interface via which the consensus layer can interact with the execution layer.
 */
public interface ExecutionLayer extends EventTransactionSupplier, SignatureTransactionCheck {
    /**
     * Submits a state signature to execution. This signature should be returned by {@link #getTransactionsForEvent()} in the
     * future.
     * <p>
     * NOTE: This method will be removed once state management moves to the execution layer.
     *
     * @param transaction the state signature transaction to submit
     */
    void submitStateSignature(@NonNull final StateSignatureTransaction transaction);

    /**
     * Notifies the execution layer that the platform status has changed.
     *
     * @param platformStatus the new platform status
     */
    void updatePlatformStatus(@NonNull final PlatformStatus platformStatus);
}
