// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.transaction.handling.internal;

import static com.swirlds.logging.legacy.LogMarker.EXCEPTION;
import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.base.time.Time;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.temporal.ChronoUnit;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.metrics.statistics.AverageTimeStat;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.state.nexus.SignedStateNexus;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.transaction.handling.TransactionCallbacks;

/**
 * Default implementation of the {@link TransactionPrehandler} interface
 */
public class DefaultTransactionPrehandler implements TransactionPrehandler {

    private static final Logger logger = LogManager.getLogger(DefaultTransactionPrehandler.class);

    private static final String RESERVATION_LABEL = "transaction prehandle";

    /**
     * A source to get the latest immutable state
     */
    private final SignedStateNexus signedStateNexus;

    /**
     * Average time spent in to prehandle each individual transaction (in microseconds)
     */
    private final AverageTimeStat preHandleTime;

    private final TransactionCallbacks transactionCallbacks;

    private final Time time;

    /**
     * Constructs a new TransactionPrehandler
     *
     * @param metrics the metrics system
     * @param time the time source
     * @param signedStateNexus provides access to the latest immutable state, may return null (implementation detail
     *                            of locking mechanism within the supplier)
     * @param transactionCallbacks the consensus state event handler
     */
    public DefaultTransactionPrehandler(
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            @NonNull final SignedStateNexus signedStateNexus,
            @NonNull final TransactionCallbacks transactionCallbacks) {
        this.time = requireNonNull(time);
        this.signedStateNexus = requireNonNull(signedStateNexus);

        preHandleTime = new AverageTimeStat(
                metrics,
                ChronoUnit.MICROS,
                INTERNAL_CATEGORY,
                "preHandleMicros",
                "average time it takes to perform preHandle (in microseconds)");
        this.transactionCallbacks = transactionCallbacks;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Queue<ScopedSystemTransaction<StateSignatureTransaction>> prehandleApplicationTransactions(
            @NonNull final PlatformEvent event) {
        final long startTime = time.nanoTime();
        final Queue<ScopedSystemTransaction<StateSignatureTransaction>> scopedSystemTransactions =
                new ConcurrentLinkedQueue<>();
        final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> consumer = scopedSystemTransactions::add;

        ReservedSignedState latestImmutableState = null;
        try {
            latestImmutableState = signedStateNexus.getState(RESERVATION_LABEL);
            while (latestImmutableState == null) {
                latestImmutableState = signedStateNexus.getState(RESERVATION_LABEL);
            }

            try {
                transactionCallbacks.onPreHandle(
                        event, latestImmutableState.get().getState(), consumer);
            } catch (final Throwable t) {
                logger.error(
                        EXCEPTION.getMarker(),
                        "error invoking ConsensusStateEventHandler.onPreHandle() for event {}",
                        event,
                        t);
            }
        } finally {
            event.signalPrehandleCompletion();
            latestImmutableState.close();

            preHandleTime.update(startTime, time.nanoTime());
        }

        return scopedSystemTransactions;
    }
}
