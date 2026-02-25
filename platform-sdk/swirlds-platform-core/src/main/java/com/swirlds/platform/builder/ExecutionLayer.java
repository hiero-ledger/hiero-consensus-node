// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.EventTransactionSupplier;
import org.hiero.consensus.model.transaction.SignatureTransactionCheck;
import org.hiero.consensus.transaction.TransactionLimits;

/**
 * An interface via which the consensus layer can interact with the execution layer (AKA the application).
 */
public interface ExecutionLayer extends EventTransactionSupplier, SignatureTransactionCheck {
    /** Default transaction limits, for applications that do not need to override them */
    TransactionLimits DEFAULT_TRANSACTION_LIMITS = new TransactionLimits(133120, 245760);

    /**
     * Submits a state signature to execution. This signature should be returned by {@link #getTransactionsForEvent()}
     * in the future.
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
    void newPlatformStatus(@NonNull final PlatformStatus platformStatus);

    /**
     * Returns the transaction size limits for the execution layer.
     * <p>
     * This is used by the consensus layer to enforce limits on transaction sizes in events received via gossip.
     * Previously, this was duplicated in both layers and had to match. Now, the execution layer provides the limits,
     * and the consensus layer uses them. Ideally, this would be configured when building the platform, but it is not
     * possible at the moment because of the Browser startup. Once the usage of Browser is removed, this can be just a
     * parameter in the PlatformBuilder.
     *
     * @return the transaction limits
     */
    default @NonNull TransactionLimits getTransactionLimits() {
        return DEFAULT_TRANSACTION_LIMITS;
    }

    /**
     * Report the amount of time that the system has been in an unhealthy state. Will receive a report of
     * {@link Duration#ZERO} when the system enters a healthy state.
     *
     * @param duration the amount of time that the system has been in an unhealthy state
     */
    void reportUnhealthyDuration(@NonNull final Duration duration);
}
