/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.swirlds.platform.event.resubmitter;

import com.hedera.hapi.platform.event.EventTransaction;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.config.StateConfig;
import com.swirlds.platform.consensus.EventWindow;
import com.swirlds.platform.event.PlatformEvent;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
    public List<EventTransaction> resubmitStaleTransactions(@NonNull final PlatformEvent event) {
        if (eventWindow == null) {
            throw new IllegalStateException("Event window is not set");
        }

        final List<EventTransaction> transactionsToResubmit = new ArrayList<>();
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
