// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static org.assertj.core.api.Assertions.fail;
import static org.hiero.consensus.wiring.framework.wires.SolderType.INJECT;
import static org.hiero.consensus.wiring.framework.wires.SolderType.OFFER;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.hiero.base.crypto.BytesSigner;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.event.creator.config.EventCreationConfig;
import org.hiero.consensus.event.creator.config.EventCreationWiringConfig;
import org.hiero.consensus.event.creator.impl.DefaultEventCreationManager;
import org.hiero.consensus.event.creator.impl.EventCreationManager;
import org.hiero.consensus.event.creator.impl.EventCreator;
import org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreator;
import org.hiero.consensus.event.intake.config.EventIntakeWiringConfig;
import org.hiero.consensus.event.intake.impl.deduplication.EventDeduplicator;
import org.hiero.consensus.event.intake.impl.deduplication.StandardEventDeduplicator;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.hashgraph.FreezePeriodChecker;
import org.hiero.consensus.hashgraph.config.HashgraphWiringConfig;
import org.hiero.consensus.hashgraph.impl.ConsensusEngine;
import org.hiero.consensus.hashgraph.impl.ConsensusEngineOutput;
import org.hiero.consensus.hashgraph.impl.DefaultConsensusEngine;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.quiescence.QuiescenceCommand;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.EventTransactionSupplier;
import org.hiero.consensus.model.transaction.SignatureTransactionCheck;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.orphan.OrphanBuffer;
import org.hiero.consensus.status.monitor.StatusMonitorModule;
import org.hiero.consensus.status.monitor.actions.PlatformStatusAction;
import org.hiero.consensus.wiring.framework.component.ComponentWiring;
import org.hiero.consensus.wiring.framework.model.DeterministicWiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModelBuilder;
import org.hiero.consensus.wiring.framework.wires.input.InputWire;
import org.hiero.consensus.wiring.framework.wires.output.OutputWire;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;

/**
 * A wiring configuration for a stripped down consensus layer that is suitable for testing.
 */
public class FalconWiring implements TimeTickReceiver {

    private static final Bytes DEFAULT_SIGNATURE = Bytes.EMPTY;

    private final DeterministicWiringModel model;
    private final ComponentWiring<EventDeduplicator, PlatformEvent> eventDeduplicatorWiring;
    private final ComponentWiring<OrphanBuffer, List<PlatformEvent>> orphanBufferWiring;
    private final ComponentWiring<ConsensusEngine, ConsensusEngineOutput> consensusEngineWiring;
    private final ComponentWiring<EventCreationManager, PlatformEvent> eventCreationManagerWiring;
    private final StatusMonitorModule statusMonitorModule;
    private final InputWire<PlatformEvent> receivedGossipEventsInputWire;
    private final OutputWire<ConsensusRound> consensusRoundOutputWire;
    private final OutputWire<EventWindow> eventWindowOutputWire;

    /**
     * Constructor for {@link FalconWiring}.
     *
     * @param configuration the configuration for the wiring
     * @param time the time source
     * @param selfId the ID of the current node
     * @param pbjRoster the roster of nodes
     * @param secureRandom the secure random number generator
     */
    public FalconWiring(
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final Roster pbjRoster,
            @NonNull final SecureRandom secureRandom) {

        final RosterWrapper roster = RosterWrapper.of(pbjRoster);

        final Metrics metrics = new NoOpMetrics();

        model = WiringModelBuilder.create(metrics, time)
                .deterministic()
                .withUncaughtExceptionHandler((_, e) -> fail("Unexpected exception in wiring framework", e))
                .build();

        final EventIntakeWiringConfig eventIntakeConfig = configuration.getConfigData(EventIntakeWiringConfig.class);

        final IntakeEventCounter intakeEventCounter = new NoOpIntakeEventCounter();

        final EventDeduplicator eventDeduplicator = new StandardEventDeduplicator(metrics, intakeEventCounter);
        eventDeduplicatorWiring =
                new ComponentWiring<>(model, EventDeduplicator.class, eventIntakeConfig.eventDeduplicator());
        eventDeduplicatorWiring.bind(eventDeduplicator);
        receivedGossipEventsInputWire = eventDeduplicatorWiring.getInputWire(EventDeduplicator::handleEvent);

        final OrphanBuffer orphanBuffer = new DefaultOrphanBuffer(metrics, intakeEventCounter);
        orphanBufferWiring = new ComponentWiring<>(model, OrphanBuffer.class, eventIntakeConfig.orphanBuffer());
        orphanBufferWiring.bind(orphanBuffer);

        final FreezePeriodChecker freezePeriodChecker = _ -> false;
        final long transactionOffsetNanos = 0L;
        final HashgraphWiringConfig hashgraphConfig = configuration.getConfigData(HashgraphWiringConfig.class);
        final ConsensusEngine consensusEngine = new DefaultConsensusEngine(
                configuration, metrics, time, roster, selfId, freezePeriodChecker, transactionOffsetNanos);
        consensusEngineWiring = new ComponentWiring<>(model, ConsensusEngine.class, hashgraphConfig.consensusEngine());
        consensusEngineWiring.bind(consensusEngine);

        final EventCreationWiringConfig eventCreationConfig =
                configuration.getConfigData(EventCreationWiringConfig.class);
        final BytesSigner byteSigner = _ -> DEFAULT_SIGNATURE;
        final EventTransactionSupplier transactionSupplier = List::of;
        final EventCreator eventCreator = new TipsetEventCreator(
                configuration, metrics, time, secureRandom, byteSigner, roster, selfId, transactionSupplier);
        final SignatureTransactionCheck signatureTransactionCheck = () -> false;
        final EventCreationManager eventCreationManager = new DefaultEventCreationManager(
                configuration, metrics, time, signatureTransactionCheck, eventCreator, roster, selfId);
        eventCreationManagerWiring =
                new ComponentWiring<>(model, EventCreationManager.class, eventCreationConfig.eventCreationManager());
        eventCreationManagerWiring.bind(eventCreationManager);

        statusMonitorModule = new StatusMonitorModule(model, configuration, metrics, time, selfId);

        eventDeduplicatorWiring.getOutputWire().solderTo(orphanBufferWiring.getInputWire(OrphanBuffer::handleEvent));

        final OutputWire<PlatformEvent> orphanBufferOutput = orphanBufferWiring.getSplitOutput();
        orphanBufferOutput.solderTo(consensusEngineWiring.getInputWire(ConsensusEngine::addEvent));
        orphanBufferOutput.solderTo(eventCreationManagerWiring.getInputWire(EventCreationManager::registerEvent));

        consensusRoundOutputWire = consensusEngineWiring
                .getOutputWire()
                .buildTransformer("ConsensusRound", "consensus output", ConsensusEngineOutput::consensusRounds)
                .buildSplitter("ConsensusRoundSplitter", "consensus rounds");
        consensusRoundOutputWire.solderTo(statusMonitorModule.consensusRoundInputWire());

        eventWindowOutputWire = consensusRoundOutputWire.buildTransformer(
                "EventWindow", "consensus round", ConsensusRound::getEventWindow);

        eventWindowOutputWire.solderTo(eventDeduplicatorWiring.getInputWire(EventDeduplicator::setEventWindow));
        eventWindowOutputWire.solderTo(orphanBufferWiring.getInputWire(OrphanBuffer::setEventWindow));
        eventWindowOutputWire.solderTo(eventCreationManagerWiring.getInputWire(EventCreationManager::setEventWindow));

        final Duration eventCreationHeartbeat =
                configuration.getConfigData(EventCreationConfig.class).period();
        model.buildHeartbeatWire(eventCreationHeartbeat)
                .solderTo(
                        eventCreationManagerWiring.getInputWire(EventCreationManager::maybeCreateEvent, "heartbeat"),
                        OFFER);
        eventCreationManagerWiring
                .getOutputWire()
                .solderTo(eventDeduplicatorWiring.getInputWire(EventDeduplicator::handleEvent));

        final OutputWire<PlatformStatus> platformStatusOutputWire = statusMonitorModule.platformStatusOutputWire();
        platformStatusOutputWire.solderTo(
                consensusEngineWiring.getInputWire(ConsensusEngine::updatePlatformStatus), INJECT);
        platformStatusOutputWire.solderTo(
                eventCreationManagerWiring.getInputWire(EventCreationManager::updatePlatformStatus));
    }

    /**
     * Get the input wire that awaits received gossip events.
     *
     * @return the input wire for received gossip events
     */
    @NonNull
    public InputWire<PlatformEvent> receivedGossipEventsInputWire() {
        return receivedGossipEventsInputWire;
    }

    /**
     * Get the output wire that provides the events to gossip: every event that leaves the orphan buffer, self-created
     * and received alike.
     *
     * @return the output wire for sent gossip events
     */
    @NonNull
    public OutputWire<PlatformEvent> sentGossipEventsOutputWire() {
        return orphanBufferWiring.getSplitOutput();
    }

    /**
     * Get the output wire that provides the event window of each consensus round.
     *
     * @return the output wire for event windows
     */
    @NonNull
    public OutputWire<EventWindow> eventWindowOutputWire() {
        return eventWindowOutputWire;
    }

    /**
     * Get the output wire that provides the results of the consensus engine.
     *
     * <p>Not used yet; kept for validating the full consensus engine output (#26795).
     *
     * @return the output wire for consensus engine outputs
     */
    @NonNull
    public OutputWire<ConsensusEngineOutput> consensusOutputWire() {
        return consensusEngineWiring.getOutputWire();
    }

    /**
     * Get the output wire that provides each consensus round.
     *
     * @return the output wire for consensus rounds
     */
    @NonNull
    public OutputWire<ConsensusRound> consensusRoundOutputWire() {
        return consensusRoundOutputWire;
    }

    /**
     * Get the output wire that provides the platform status computed by the status monitor.
     *
     * @return the output wire for platform status changes
     */
    @NonNull
    public OutputWire<PlatformStatus> platformStatusOutputWire() {
        return statusMonitorModule.platformStatusOutputWire();
    }

    /**
     * Get the input wire that accepts actions for the status monitor.
     *
     * @return the input wire for platform status actions
     */
    @NonNull
    public InputWire<PlatformStatusAction> platformStatusActionInputWire() {
        return statusMonitorModule.platformStatusActionInputWire();
    }

    /**
     * Get the input wire that passes quiescence commands to the status monitor.
     *
     * @return the input wire for quiescence commands
     */
    @NonNull
    public InputWire<QuiescenceCommand> quiescenceCommandInputWire() {
        return statusMonitorModule.quiescenceCommandInputWire();
    }

    /**
     * Starts the wiring model.
     */
    public void start() {
        model.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        model.tick();
    }
}
