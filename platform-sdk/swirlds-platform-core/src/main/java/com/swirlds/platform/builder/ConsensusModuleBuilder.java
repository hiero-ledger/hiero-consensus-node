// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.builder;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.hapi.node.state.roster.RoundRosterPair;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.common.io.utility.SimpleRecycleBin;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.Supplier;
import org.hiero.consensus.crypto.KeysAndCertsGenerator;
import org.hiero.consensus.crypto.SigningSchema;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.event.creator.EventCreatorModule;
import org.hiero.consensus.event.intake.EventIntakeModule;
import org.hiero.consensus.hashgraph.HashgraphModule;
import org.hiero.consensus.io.RecycleBin;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.metrics.statistics.EventPipelineTracker;
import org.hiero.consensus.model.node.KeysAndCerts;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.pces.PcesModule;
import org.hiero.consensus.roster.RosterHistory;
import org.hiero.consensus.state.signed.ReservedSignedState;
import org.hiero.consensus.transaction.TransactionLimits;

/**
 * A builder for consensus modules using the ServiceLoader mechanism.
 */
public class ConsensusModuleBuilder {

    private ConsensusModuleBuilder() {}

    /**
     * Create an instance of the {@link EventCreatorModule} using {@link ServiceLoader}.
     *
     * @return an instance of {@code EventCreatorModule}
     * @throws IllegalStateException if no implementation is found
     */
    public static EventCreatorModule createEventCreatorModule() {
        return ServiceLoader.load(EventCreatorModule.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No EventCreatorModule implementation found!"));
    }

    /**
     * Create and initialize a no-op instance of the {@link EventCreatorModule}.
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @return an initialized no-op instance of {@code EventCreatorModule}
     */
    public static EventCreatorModule createNoOpEventCreatorModule(
            @NonNull final WiringModel model, @NonNull final Configuration configuration) {
        final Metrics metrics = new NoOpMetrics();
        final Time time = Time.getCurrent();
        final NodeId selfId = NodeId.FIRST_NODE_ID;
        final SecureRandom random = new SecureRandom();
        final KeysAndCerts keysAndCerts;
        try {
            keysAndCerts = KeysAndCertsGenerator.generate(selfId, SigningSchema.ED25519, random, random);
        } catch (final Exception e) {
            throw new RuntimeException("Exception thrown while creating dummy KeysAndCerts", e);
        }
        final RosterEntry rosterEntry = new RosterEntry(selfId.id(), 0L, Bytes.EMPTY, List.of());
        final Roster roster = new Roster(List.of(rosterEntry));

        final EventCreatorModule eventCreatorModule = createEventCreatorModule();
        eventCreatorModule.initialize(
                model, configuration, metrics, time, random, keysAndCerts, roster, selfId, List::of, () -> false);
        return eventCreatorModule;
    }

    /**
     * Create an instance of the {@link EventIntakeModule} using {@link ServiceLoader}.
     *
     * @return an instance of {@code EventIntakeModule}
     * @throws IllegalStateException if no implementation is found
     */
    public static EventIntakeModule createEventIntakeModule() {
        return ServiceLoader.load(EventIntakeModule.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No EventIntakeModule implementation found!"));
    }

    /**
     * Create and initialize a no-op instance of the {@link EventIntakeModule}.
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @return an initialized no-op instance of {@code EventIntakeModule}
     */
    public static EventIntakeModule createNoOpEventIntakeModule(
            @NonNull final WiringModel model, @NonNull final Configuration configuration) {
        final Metrics metrics = new NoOpMetrics();
        final Time time = Time.getCurrent();
        final NodeId selfId = NodeId.FIRST_NODE_ID;
        final RosterEntry rosterEntry = new RosterEntry(selfId.id(), 0L, Bytes.EMPTY, List.of());
        final Roster roster = new Roster(List.of(rosterEntry));
        final RosterHistory rosterHistory =
                new RosterHistory(List.of(new RoundRosterPair(0L, Bytes.EMPTY)), Map.of(Bytes.EMPTY, roster));
        final IntakeEventCounter intakeEventCounter = new NoOpIntakeEventCounter();
        final TransactionLimits transactionLimits = new TransactionLimits(0, 0);
        final EventPipelineTracker eventPipelineTracker = null;

        final EventIntakeModule eventIntakeModule = createEventIntakeModule();
        eventIntakeModule.initialize(
                model,
                configuration,
                metrics,
                time,
                rosterHistory,
                intakeEventCounter,
                transactionLimits,
                eventPipelineTracker);
        return eventIntakeModule;
    }

    /**
     * Create an instance of the {@link PcesModule} using {@link ServiceLoader}.
     *
     * @return an instance of {@code PcesModule}
     * @throws IllegalStateException if no implementation is found
     */
    @NonNull
    public static PcesModule createPcesModule() {
        return ServiceLoader.load(PcesModule.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No PcesModule implementation found!"));
    }

    /**
     * Create and initialize a no-op instance of the {@link PcesModule}.
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @return an initialized no-op instance of {@code PcesModule}
     */
    @NonNull
    public static PcesModule createNoOpPcesModule(
            @NonNull final WiringModel model, @NonNull final Configuration configuration) {
        final Metrics metrics = new NoOpMetrics();
        final Time time = Time.getCurrent();
        final NodeId selfId = NodeId.FIRST_NODE_ID;
        final RecycleBin recycleBin = new SimpleRecycleBin();
        final long startingRound = 0L;
        final Runnable flushIntake = () -> {};
        final Runnable flushTransactionHandling = () -> {};
        final Supplier<ReservedSignedState> latestImmutableStateSupplier = ReservedSignedState::createNullReservation;
        final EventPipelineTracker eventPipelineTracker = null;

        final PcesModule pcesModule = createPcesModule();
        pcesModule.initialize(
                model,
                configuration,
                metrics,
                time,
                selfId,
                recycleBin,
                startingRound,
                flushIntake,
                flushTransactionHandling,
                latestImmutableStateSupplier,
                eventPipelineTracker);
        return pcesModule;
    }

    /**
     * Create an instance of the {@link HashgraphModule} using {@link ServiceLoader}.
     *
     * @return an instance of {@code HashgraphModule}
     * @throws IllegalStateException if no implementation is found
     */
    public static HashgraphModule createHashgraphModule() {
        return ServiceLoader.load(HashgraphModule.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No HashgraphModule implementation found!"));
    }

    /**
     * Create and initialize a no-op instance of the {@link HashgraphModule}.
     *
     * @param model the wiring model
     * @param configuration the configuration
     * @return an initialized no-op instance of {@code HashgraphModule}
     */
    public static HashgraphModule createNoOpHashgraphModule(
            @NonNull final WiringModel model, @NonNull final Configuration configuration) {
        final Metrics metrics = new NoOpMetrics();
        final Time time = Time.getCurrent();
        final NodeId selfId = NodeId.FIRST_NODE_ID;
        final RosterEntry rosterEntry = new RosterEntry(selfId.id(), 0L, Bytes.EMPTY, List.of());
        final Roster roster = new Roster(List.of(rosterEntry));
        final HashgraphModule hashgraphModule = createHashgraphModule();
        final EventPipelineTracker eventPipelineTracker = null;
        hashgraphModule.initialize(
                model, configuration, metrics, time, roster, selfId, instant -> false, eventPipelineTracker);
        return hashgraphModule;
    }
}
