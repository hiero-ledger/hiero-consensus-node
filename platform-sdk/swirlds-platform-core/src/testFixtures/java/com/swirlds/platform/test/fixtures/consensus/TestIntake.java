// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus;

import static com.swirlds.component.framework.wires.SolderType.INJECT;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.DeterministicWiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.platform.components.DefaultEventWindowManager;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import com.swirlds.platform.wiring.components.PassThroughWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.gossip.impl.gossip.NoOpIntakeEventCounter;
import org.hiero.consensus.hashgraph.FreezePeriodChecker;
import org.hiero.consensus.hashgraph.config.ConsensusConfig;
import org.hiero.consensus.hashgraph.impl.ConsensusEngine;
import org.hiero.consensus.hashgraph.impl.ConsensusEngineOutput;
import org.hiero.consensus.hashgraph.impl.DefaultConsensusEngine;
import org.hiero.consensus.hashgraph.impl.consensus.SyntheticSnapshot;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.orphan.OrphanBuffer;
import org.hiero.consensus.round.EventWindowUtils;

/**
 * Event intake with consensus and shadowgraph, used for testing
 */
public class TestIntake {
    private final ConsensusOutput output;

    private final ComponentWiring<EventHasher, PlatformEvent> hasherWiring;
    private final ComponentWiring<OrphanBuffer, List<PlatformEvent>> orphanBufferWiring;
    private final ComponentWiring<ConsensusEngine, ConsensusEngineOutput> consensusEngineWiring;
    private final Queue<Throwable> componentExceptions = new LinkedList<>();
    private final DeterministicWiringModel model;
    private final int roundsNonAncient;
    private final AtomicReference<FreezePeriodChecker> freezeCheckHolder = new AtomicReference<>(i -> false);
    private final FakeTime time = new FakeTime(Duration.of(1, ChronoUnit.SECONDS));

    /**
     * @param platformContext the platform context used to configure this intake.
     * @param roster the roster used by this intake
     */
    public TestIntake(@NonNull final PlatformContext platformContext, @NonNull final Roster roster) {
        final NodeId selfId = NodeId.of(0);
        roundsNonAncient = platformContext
                .getConfiguration()
                .getConfigData(ConsensusConfig.class)
                .roundsNonAncient();

        output = new ConsensusOutput();

        model = WiringModelBuilder.create(new NoOpMetrics(), time)
                .deterministic()
                .build();

        hasherWiring = new ComponentWiring<>(model, EventHasher.class, scheduler("eventHasher"));
        final EventHasher eventHasher = new DefaultEventHasher();
        hasherWiring.bind(eventHasher);

        final PassThroughWiring<PlatformEvent> postHashCollectorWiring =
                new PassThroughWiring(model, "PlatformEvent", "postHashCollector", TaskSchedulerType.DIRECT);

        final IntakeEventCounter intakeEventCounter = new NoOpIntakeEventCounter();
        final OrphanBuffer orphanBuffer = new DefaultOrphanBuffer(platformContext.getMetrics(), intakeEventCounter);
        orphanBufferWiring = new ComponentWiring<>(model, OrphanBuffer.class, scheduler("orphanBuffer"));
        orphanBufferWiring.bind(orphanBuffer);

        final var localFreezeCheck = new FreezePeriodChecker() {
            @Override
            public boolean isInFreezePeriod(@NonNull final Instant timestamp) {
                return freezeCheckHolder.get().isInFreezePeriod(timestamp);
            }
        };

        final ConsensusEngine consensusEngine = new DefaultConsensusEngine(
                platformContext.getConfiguration(),
                platformContext.getMetrics(),
                platformContext.getTime(),
                roster,
                selfId,
                localFreezeCheck);

        consensusEngineWiring = new ComponentWiring<>(model, ConsensusEngine.class, scheduler("consensusEngine"));
        consensusEngineWiring.bind(consensusEngine);

        final ComponentWiring<EventWindowManager, EventWindow> eventWindowManagerWiring =
                new ComponentWiring<>(model, EventWindowManager.class, scheduler("eventWindowManager"));
        eventWindowManagerWiring.bind(new DefaultEventWindowManager());

        hasherWiring.getOutputWire().solderTo(postHashCollectorWiring.getInputWire());
        postHashCollectorWiring.getOutputWire().solderTo(orphanBufferWiring.getInputWire(OrphanBuffer::handleEvent));
        final OutputWire<PlatformEvent> splitOutput = orphanBufferWiring.getSplitOutput();
        splitOutput.solderTo(consensusEngineWiring.getInputWire(ConsensusEngine::addEvent));

        final OutputWire<ConsensusRound> consensusRoundOutputWire = consensusEngineWiring
                .getOutputWire()
                .buildTransformer("getConsRounds", "consensusEngineOutput", ConsensusEngineOutput::consensusRounds)
                .buildSplitter("consensusRoundsSplitter", "consensusRounds");
        consensusRoundOutputWire.solderTo(
                eventWindowManagerWiring.getInputWire(EventWindowManager::extractEventWindow));
        consensusEngineWiring
                .getOutputWire()
                .solderTo("consensusOutputTestTool", "consensus output", output::consensusEngineOutput);

        eventWindowManagerWiring
                .getOutputWire()
                .solderTo(orphanBufferWiring.getInputWire(OrphanBuffer::setEventWindow), INJECT);

        // Ensure unsoldered wires are created.
        hasherWiring.getInputWire(EventHasher::hashEvent);

        // Make sure this unsoldered wire is properly built
        consensusEngineWiring.getInputWire(ConsensusEngine::outOfBandSnapshotUpdate);

        model.start();
    }

    /**
     * Link an event to its parents and add it to consensus and shadowgraph
     *
     * @param event the event to add
     */
    public void addEvent(@NonNull final PlatformEvent event) {
        hasherWiring.getInputWire(EventHasher::hashEvent).put(event);
        output.eventAdded(event);
        model.doAllWork();
        throwComponentExceptionsIfAny();
    }

    /**
     * @return a queue of all rounds that have reached consensus
     */
    public @NonNull LinkedList<ConsensusRound> getConsensusRounds() {
        return output.getConsensusRounds();
    }

    public void loadSnapshot(@NonNull final ConsensusSnapshot snapshot) {
        final EventWindow eventWindow = EventWindowUtils.createEventWindow(snapshot, roundsNonAncient);

        orphanBufferWiring.getInputWire(OrphanBuffer::setEventWindow).put(eventWindow);
        consensusEngineWiring
                .getInputWire(ConsensusEngine::outOfBandSnapshotUpdate)
                .put(snapshot);
        throwComponentExceptionsIfAny();
    }

    public @NonNull ConsensusOutput getOutput() {
        return output;
    }

    public void setFreezeCheck(@NonNull final FreezePeriodChecker freezeChecker) {
        this.freezeCheckHolder.set(freezeChecker);
    }

    public void reset() {
        time.reset();
        loadSnapshot(SyntheticSnapshot.getGenesisSnapshot());
        output.clear();
    }

    private void throwComponentExceptionsIfAny() {
        componentExceptions.stream().findFirst().ifPresent(t -> {
            throw new RuntimeException(t);
        });
    }

    public <X> TaskScheduler<X> scheduler(final String name) {
        return model.<X>schedulerBuilder(name)
                .withType(TaskSchedulerType.SEQUENTIAL)
                // This is needed because of the catch in StandardOutputWire.forward()
                // if we throw the exception, it will be caught by it and will not fail the test
                .withUncaughtExceptionHandler((t, e) -> componentExceptions.add(e))
                .build();
    }
}
