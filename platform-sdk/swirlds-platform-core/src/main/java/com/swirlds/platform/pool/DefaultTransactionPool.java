// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.pool;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.Objects;
import org.hiero.consensus.model.status.PlatformStatus;

/**
 * A default implementation of a {@link TransactionPool}.
 */
public class DefaultTransactionPool implements TransactionPool {

    private final TransactionPoolNexus transactionPoolNexus;

    /**
     * Constructor.
     *
     * @param transactionPoolNexus the transaction pool nexus
     */
    public DefaultTransactionPool(@NonNull final TransactionPoolNexus transactionPoolNexus) {
        this.transactionPoolNexus = Objects.requireNonNull(transactionPoolNexus);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void submitSystemTransaction(@NonNull final Bytes payload) {
        Objects.requireNonNull(payload);
        transactionPoolNexus.submitTransaction(payload, true);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updatePlatformStatus(@NonNull final PlatformStatus platformStatus) {
        Objects.requireNonNull(platformStatus);
        transactionPoolNexus.updatePlatformStatus(platformStatus);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void reportUnhealthyDuration(@NonNull final Duration duration) {
        Objects.requireNonNull(duration);
        transactionPoolNexus.reportUnhealthyDuration(duration);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void clear() {
        transactionPoolNexus.clear();
    }
}
