// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.app;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.platform.event.StateSignatureTransaction;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.config.api.Configuration;
import com.swirlds.platform.state.ConsensusStateEventHandler;
import com.swirlds.platform.system.InitTrigger;
import com.swirlds.platform.system.Platform;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.Event;
import org.hiero.consensus.model.hashgraph.Round;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.model.transaction.ConsensusTransaction;
import org.hiero.consensus.model.transaction.ScopedSystemTransaction;
import org.hiero.consensus.model.transaction.Transaction;
import org.hiero.otter.fixtures.app.services.consistency.ConsistencyService;
import org.hiero.otter.fixtures.app.services.iss.IssService;
import org.hiero.otter.fixtures.app.services.platform.PlatformStateService;
import org.hiero.otter.fixtures.app.services.roster.RosterService;
import org.hiero.otter.fixtures.app.state.OtterStateInitializer;

/**
 * The main entry point for the Otter application. This class is instantiated by the platform when the application is
 * started. It creates the services that make up the application and routes events and rounds to those services.
 */
@SuppressWarnings("removal")
public class OtterApp implements ConsensusStateEventHandler<OtterAppState> {

    private static final Logger log = LogManager.getLogger();

    public static final String APP_NAME = "OtterApp";
    public static final String SWIRLD_NAME = "123";

    private final SemanticVersion version;
    private final List<OtterService> allServices;
    private final List<OtterService> appServices;

    /**
     * The number of milliseconds to sleep per handled consensus round. Sleeping for long enough over a period of time
     * will cause a backup of data in the platform as cause it to fall into CHECKING or even BEHIND.
     * <p>
     * Held in an {@link AtomicLong} because value is set by the container handler thread and is read by the consensus
     * node's handle thread.
     */
    private final AtomicLong syntheticBottleneckMillis = new AtomicLong(0);

    /**
     * Create the app and its services.
     *
     * @param version the software version to set in the state
     */
    public OtterApp(@NonNull final SemanticVersion version) {
        this.version = requireNonNull(version);

        final IssService issService = new IssService();
        final ConsistencyService consistencyService = new ConsistencyService();

        this.appServices = List.of(consistencyService, issService);
        this.allServices = List.of(consistencyService, issService, new PlatformStateService(), new RosterService());
    }

    /**
     * Get the list of services that are part of the application only, and not by the platform.
     *
     * @return the list of app services
     */
    @NonNull
    public List<OtterService> appServices() {
        return appServices;
    }

    /**
     * Get the list of all services, both platform and app.
     *
     * @return the list of services
     */
    @NonNull
    public List<OtterService> allServices() {
        return allServices;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPreHandle(
            @NonNull final Event event,
            @NonNull final OtterAppState state,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        for (final OtterService service : allServices) {
            service.preHandleEvent(event);
        }
        final Iterator<Transaction> transactionIterator = event.transactionIterator();
        while (transactionIterator.hasNext()) {
            try {
                final OtterTransaction transaction = OtterTransaction.parseFrom(
                        transactionIterator.next().getApplicationTransaction().toInputStream());
                for (final OtterService service : allServices) {
                    service.preHandleTransaction(event, transaction, callback);
                }
            } catch (final IOException ex) {
                log.error(
                        "Unable to parse OtterTransaction created by node {}",
                        event.getCreatorId().id(),
                        ex);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onHandleConsensusRound(
            @NonNull final Round round,
            @NonNull final OtterAppState state,
            @NonNull final Consumer<ScopedSystemTransaction<StateSignatureTransaction>> callback) {
        for (final OtterService service : allServices) {
            service.onRoundStart(state.getWritableStates(service.name()), round);
        }

        for (final ConsensusEvent consensusEvent : round) {
            for (final OtterService service : allServices) {
                service.onEventStart(state.getWritableStates(service.name()), consensusEvent);
            }
            final Iterator<ConsensusTransaction> transactionIterator = consensusEvent.consensusTransactionIterator();
            while (transactionIterator.hasNext()) {
                final ConsensusTransaction consensusTransaction = transactionIterator.next();
                try {
                    final OtterTransaction transaction = OtterTransaction.parseFrom(
                            consensusTransaction.getApplicationTransaction().toInputStream());
                    for (final OtterService service : allServices) {
                        service.handleTransaction(
                                state.getWritableStates(service.name()),
                                consensusEvent,
                                transaction,
                                consensusTransaction.getConsensusTimestamp(),
                                callback);
                    }
                } catch (final IOException ex) {
                    log.error(
                            "Unable to parse OtterTransaction created by node {}",
                            consensusEvent.getCreatorId().id(),
                            ex);
                }
            }

            for (final OtterService service : allServices) {
                service.onEventComplete(consensusEvent);
            }
        }

        for (final OtterService service : allServices) {
            service.onRoundComplete(round);
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
        final Configuration configuration = platform.getContext().getConfiguration();
        if (state.getReadableStates(ConsistencyService.NAME).isEmpty()) {
            OtterStateInitializer.initOtterAppState(state, version, appServices);
        }

        for (final OtterService service : allServices) {
            service.initialize(trigger, platform.getSelfId(), configuration, state);
        }
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

    public void destroy() {
        for (final OtterService service : allServices) {
            service.destroy();
        }
    }
}
