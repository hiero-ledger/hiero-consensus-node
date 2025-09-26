// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.otter.fixtures.app.services.consistency.ConsistencyService;
import org.hiero.otter.fixtures.app.services.platform.PlatformStateService;
import org.hiero.otter.fixtures.app.services.roster.RosterService;

/**
 * Simple application that can process all transactions required to run tests on Turtle
 */
@SuppressWarnings("removal")
public class OtterApp implements ConsensusStateEventHandler<OtterAppState> {

    public static final String APP_NAME = "org.hiero.otter.fixtures.app.OtterApp";
    public static final String SWIRLD_NAME = "123";

    private final List<OtterService> services;

    /**
     * The number of milliseconds to sleep per handled consensus round. Sleeping for long enough over a period of time
     * will cause a backup of data in the platform as cause it to fall into CHECKING or even BEHIND.
     * <p>
     * Held in an {@link AtomicLong} because value is set by the container handler thread and is read by the consensus
     * node's handle thread.
     */
    private final AtomicLong syntheticBottleneckMillis = new AtomicLong(0);

    private final ConsistencyService consistencyService = new ConsistencyService();

    /**
     * Create the app and its services.
     */
    public OtterApp() {
        this.services = List.of(new PlatformStateService(), new RosterService(), consistencyService);
    }

    /**
     * Get the list of services used by this app.
     *
     * @return the list of services
     */
    @NonNull
    public List<OtterService> services() {
        return services;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPreHandle(
            @NonNull final Event event,
            @NonNull final OtterAppState state,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        consistencyService.recordPreHandleTransactions(event);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onHandleConsensusRound(
            @NonNull final Round round,
            @NonNull final OtterAppState state,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        for (final OtterService service : services) {
            service.onRound(state.getWritableStates(service.name()), round);
        }

        for (final ConsensusEvent consensusEvent : round) {
            for (final OtterService service : services) {
                service.onEvent(state.getWritableStates(service.name()), consensusEvent);
            }
            for (final Iterator<ConsensusTransaction> transactionIterator =
                            consensusEvent.consensusTransactionIterator();
                    transactionIterator.hasNext(); ) {
                final ConsensusTransaction transaction = transactionIterator.next();
                for (final OtterService service : services) {
                    service.onTransaction(
                            state.getWritableStates(service.name()), consensusEvent, transaction, callback);
                }
            }
        }

        state.commitState();

        maybeDoBottleneck();
    }

    /**
     * Engages a bottleneck by sleeping for the configured number of milliseconds. Does nothing if the number of
     * milliseconds to sleep is zero or negative.
     */
    private void maybeDoBottleneck() {
        final long millisToSleep = syntheticBottleneckMillis.get();
        if (millisToSleep > 0) {
            try {
                Thread.sleep(millisToSleep);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onSealConsensusRound(@NonNull final Round round, @NonNull final OtterAppState state) {
        return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onStateInitialized(
            @NonNull final OtterAppState state,
            @NonNull final Platform platform,
            @NonNull final InitTrigger trigger,
            @Nullable final SemanticVersion previousVersion) {
        consistencyService.initialize();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onUpdateWeight(
            @NonNull final OtterAppState state,
            @NonNull final AddressBook configAddressBook,
            @NonNull final PlatformContext context) {
        // No weight update required yet
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onNewRecoveredState(@NonNull final OtterAppState recoveredState) {
        // No new recovered state required yet
    }

    /**
     * Updates the synthetic bottleneck value.
     *
     * @param millisToSleepPerRound the number of milliseconds to sleep per round
     */
    public void updateSyntheticBottleneck(final long millisToSleepPerRound) {
        this.syntheticBottleneckMillis.set(millisToSleepPerRound);
    }
}
