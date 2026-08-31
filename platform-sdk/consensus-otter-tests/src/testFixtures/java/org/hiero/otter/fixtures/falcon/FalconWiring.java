// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon;

import static org.assertj.core.api.Assertions.fail;
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
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.model.transaction.EventTransactionSupplier;
import org.hiero.consensus.model.transaction.SignatureTransactionCheck;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.orphan.OrphanBuffer;
import org.hiero.consensus.wiring.framework.component.ComponentWiring;
import org.hiero.consensus.wiring.framework.model.DeterministicWiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModelBuilder;
import org.hiero.consensus.wiring.framework.wires.input.InputWire;
import org.hiero.consensus.wiring.framework.wires.output.OutputWire;
import org.hiero.otter.fixtures.internal.AbstractTimeManager.TimeTickReceiver;

/**
 * A wiring configuration for a stripped down consensus layer that is suitable for testing. It wires together the event intake, orphan buffer, consensus engine, and event creation manager components.
 */
public class FalconWiring implements TimeTickReceiver {

    private static final Bytes DEFAULT_SIGNATURE = Bytes.EMPTY;

    private final DeterministicWiringModel model;
    private final ComponentWiring<OrphanBuffer, List<PlatformEvent>> orphanBufferWiring;
    private final ComponentWiring<ConsensusEngine, ConsensusEngineOutput> consensusEngineWiring;
    private final ComponentWiring<EventCreationManager, PlatformEvent> eventCreationManagerWiring;
    private final OutputWire<EventWindow> eventWindowOutputWire;

    /**
     * Constructor for {@link FalconWiring}.
     *
     * @param configuration the configuration for the wiring
     * @param time the time source
     * @param selfId the ID of the current node
     * @param roster the roster of nodes
     * @param secureRandom the secure random number generator
     */
    public FalconWiring(
            @NonNull final Configuration configuration,
            @NonNull final Time time,
            @NonNull final NodeId selfId,
            @NonNull final Roster roster,
            @NonNull final SecureRandom secureRandom) {

        final Metrics metrics = new NoOpMetrics();

        model = WiringModelBuilder.create(metrics, time)
                .deterministic()
                .withUncaughtExceptionHandler((t, e) -> fail("Unexpected exception in wiring framework", e))
                .build();

        final EventIntakeWiringConfig eventIntakeConfig = configuration.getConfigData(EventIntakeWiringConfig.class);

        final IntakeEventCounter intakeEventCounter = new NoOpIntakeEventCounter();
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

        final OutputWire<PlatformEvent> orphanBufferOutput = orphanBufferWiring.getSplitOutput();
        orphanBufferOutput.solderTo(consensusEngineWiring.getInputWire(ConsensusEngine::addEvent));
        orphanBufferOutput.solderTo(eventCreationManagerWiring.getInputWire(EventCreationManager::registerEvent));

        eventWindowOutputWire = consensusEngineWiring
                .getOutputWire()
                .buildTransformer("ConsensusRound", "consensus output", ConsensusEngineOutput::consensusRounds)
                .<ConsensusRound>buildSplitter("ConsensusRoundSplitter", "consensus rounds")
                .buildTransformer("EventWindow", "consensus round", ConsensusRound::getEventWindow);

        eventWindowOutputWire.solderTo(orphanBufferWiring.getInputWire(OrphanBuffer::setEventWindow));
        eventWindowOutputWire.solderTo(eventCreationManagerWiring.getInputWire(EventCreationManager::setEventWindow));

        final Duration eventCreationHeartbeat =
                configuration.getConfigData(EventCreationConfig.class).period();
        model.buildHeartbeatWire(eventCreationHeartbeat)
                .solderTo(
                        eventCreationManagerWiring.getInputWire(EventCreationManager::maybeCreateEvent, "heartbeat"),
                        OFFER);
        eventCreationManagerWiring.getOutputWire().solderTo(orphanBufferWiring.getInputWire(OrphanBuffer::handleEvent));

        consensusEngineWiring.getInputWire(ConsensusEngine::updatePlatformStatus);
        eventCreationManagerWiring.getInputWire(EventCreationManager::updatePlatformStatus);
    }

    /**
     * Get the input wire that awaits received gossip events.
     *
     * @return the input wire for received gossip events
     */
    @NonNull
    public InputWire<PlatformEvent> receivedGossipEventsInputWire() {
        return orphanBufferWiring.getInputWire(OrphanBuffer::handleEvent);
    }

    /**
     * Get the output wire that provides created self-events for gossiping.
     *
     * @return the output wire for sent gossip events
     */
    @NonNull
    public OutputWire<PlatformEvent> sentGossipEventsOutputWire() {
        return eventCreationManagerWiring.getOutputWire();
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
     * @return the output wire for consensus engine outputs
     */
    @NonNull
    public OutputWire<ConsensusEngineOutput> consensusOutputWire() {
        return consensusEngineWiring.getOutputWire();
    }

    /**
     * Starts the wiring model and sets the platform status to ACTIVE.
     */
    public void start() {
        model.start();
        consensusEngineWiring
                .getInputWire(ConsensusEngine::updatePlatformStatus)
                .inject(PlatformStatus.ACTIVE);
        eventCreationManagerWiring
                .getInputWire(EventCreationManager::updatePlatformStatus)
                .inject(PlatformStatus.ACTIVE);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void tick(@NonNull final Instant now) {
        model.tick();
    }
}
