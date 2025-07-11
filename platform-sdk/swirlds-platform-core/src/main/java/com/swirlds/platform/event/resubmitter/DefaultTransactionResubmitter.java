// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.resubmitter;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.config.StateConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.EventWindow;

/**
 * A default implementation of {@link TransactionResubmitter}.
 */
public class DefaultTransactionResubmitter implements TransactionResubmitter {

    private EventWindow eventWindow;
    private final long maxSignatureResubmitAge;

    private final TransactionResubmitterMetrics metrics;

    /**
     * Constructor.
     *
     * @param platformContext the platform context
     */
    public DefaultTransactionResubmitter(@NonNull final PlatformContext platformContext) {
        maxSignatureResubmitAge = platformContext
                .getConfiguration()
                .getConfigData(StateConfig.class)
                .maxSignatureResubmitAge();

        metrics = new TransactionResubmitterMetrics(platformContext);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @NonNull
    public List<Bytes> resubmitStaleTransactions(@NonNull final PlatformEvent event) {
        if (eventWindow == null) {
            throw new IllegalStateException("Event window is not set");
        }

        final List<Bytes> transactionsToResubmit = new ArrayList<>();
        // turning off this functionality for now
        //        final Iterator<Transaction> iterator = event.transactionIterator();
        //        while (iterator.hasNext()) {
        //            final Bytes transaction = iterator.next().getTransaction();
        //            if (Objects.equals(transaction.transaction().kind(),
        // TransactionOneOfType.STATE_SIGNATURE_TRANSACTION)) {
        //                final StateSignatureTransaction payload =
        //                        transaction.transaction().as();
        //                final long transactionAge = eventWindow.getLatestConsensusRound() - payload.round();
        //
        //                if (transactionAge <= maxSignatureResubmitAge) {
        //                    transactionsToResubmit.add(transaction);
        //                    metrics.reportResubmittedSystemTransaction();
        //                } else {
        //                    metrics.reportAbandonedSystemTransaction();
        //                }
        //            }
        //        }

        return transactionsToResubmit;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void updateEventWindow(@NonNull final EventWindow eventWindow) {
        this.eventWindow = Objects.requireNonNull(eventWindow);
    }
}
