// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.spi.workflows;

import com.hedera.hapi.node.transaction.TransactionBody;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Lets a service do genesis entity creations that must be legible in the block stream as specific HAPI
 * transactions for mirror node importers that were designed for the semantics of the original record
 * stream.
 */
public interface SystemContext {
    /**
     * Dispatches a transaction body customized by the given specification to the appropriate service using
     * the requested next entity number, which must be less than the first user entity number.
     * @param spec the transaction body
     * @param entityNum the entity number
     */
    default void dispatchCreation(@NonNull Consumer<TransactionBody.Builder> spec, long entityNum) {
        throw new UnsupportedOperationException();
    }

    /**
     * Dispatches a transaction to the appropriate service using the requested next entity number, which
     * must be less than the first user entity number.
     *
     * @param txBody the transaction body
     * @param entityNum the entity number
     * @throws IllegalArgumentException if the entity number is not less than the first user entity number
     */
    void dispatchCreation(@NonNull TransactionBody txBody, long entityNum);

    /**
     * Dispatches a transaction body customized by the given specification to the appropriate service.
     *
     * @param spec the transaction body
     * @throws IllegalArgumentException if the entity number is not less than the first user entity number
     */
    void dispatchAdmin(@NonNull Consumer<TransactionBody.Builder> spec);

    /**
     * The {@link Configuration} at genesis.
     *
     * @return The configuration to use.
     */
    @NonNull
    Configuration configuration();

    /**
     * The {@link NetworkInfo} at genesis.
     *
     * @return The network info to use.
     */
    @NonNull
    NetworkInfo networkInfo();

    /**
     * The consensus time now.
     */
    @NonNull
    Instant now();

    /**
     * Checks if there are any dispatches remaining in the current context.
     * @return true if there are dispatches remaining, false otherwise
     */
    boolean hasDispatchesRemaining();
}
