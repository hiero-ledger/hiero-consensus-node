// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus;

import static com.swirlds.component.framework.wires.SolderType.INJECT;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.time.Time;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.metrics.noop.NoOpMetrics;
import com.swirlds.component.framework.component.ComponentWiring;
import com.swirlds.component.framework.model.WiringModel;
import com.swirlds.component.framework.model.WiringModelBuilder;
import com.swirlds.component.framework.schedulers.TaskScheduler;
import com.swirlds.component.framework.schedulers.builders.TaskSchedulerType;
import com.swirlds.component.framework.wires.output.OutputWire;
import com.swirlds.platform.components.DefaultEventWindowManager;
import com.swirlds.platform.components.EventWindowManager;
import com.swirlds.platform.components.consensus.ConsensusEngine;
import com.swirlds.platform.components.consensus.ConsensusEngineOutput;
import com.swirlds.platform.components.consensus.DefaultConsensusEngine;
import com.swirlds.platform.consensus.ConsensusConfig;
import com.swirlds.platform.consensus.EventWindowUtils;
import com.swirlds.platform.consensus.SyntheticSnapshot;
import com.swirlds.platform.event.orphan.DefaultOrphanBuffer;
import com.swirlds.platform.event.orphan.OrphanBuffer;
import com.swirlds.platform.freeze.FreezeCheckHolder;
import com.swirlds.platform.gossip.IntakeEventCounter;
import com.swirlds.platform.gossip.NoOpIntakeEventCounter;
import com.swirlds.platform.test.fixtures.consensus.framework.ConsensusOutput;
import com.swirlds.platform.wiring.components.PassThroughWiring;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;

/**
 * Event intake with consensus and shadowgraph, used for testing
 */
public class TestIntake {
    private final ConsensusOutput output;

    private final ComponentWiring<EventHasher, PlatformEvent> hasherWiring;
    private final ComponentWiring<OrphanBuffer, List<PlatformEvent>> orphanBufferWiring;
    private final ComponentWiring<ConsensusEngine, ConsensusEngineOutput> consensusEngineWiring;
    private final Queue<Throwable> componentExceptions = new LinkedList<>();
    private final WiringModel model;
    private final int roundsNonAncient;
    private final FreezeCheckHolder freezeCheckHolder;

    /**
     * @param platformContext the platform context used to configure this intake.
     * @param roster     the roster used by this intake
     */
    public TestIntake(@NonNull final PlatformContext platformContext, @NonNull final Roster roster) {
        final NodeId selfId = NodeId.of(0);
        roundsNonAncient = platformContext
                .getConfiguration()
                .getConfigData(ConsensusConfig.class)
                .roundsNonAncient();

        output = new ConsensusOutput();

        model = WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent()).build();

        hasherWiring = new ComponentWiring<>(model, EventHasher.class, directScheduler("eventHasher"));
        final EventHasher eventHasher = new DefaultEventHasher();
        hasherWiring.bind(eventHasher);

        final PassThroughWiring<PlatformEvent> postHashCollectorWiring =
                new PassThroughWiring(model, "PlatformEvent", "postHashCollector", TaskSchedulerType.DIRECT);

        final IntakeEventCounter intakeEventCounter = new NoOpIntakeEventCounter();
        final OrphanBuffer orphanBuffer = new DefaultOrphanBuffer(
                platformContext.getConfiguration(), platformContext.getMetrics(), intakeEventCounter);
        orphanBufferWiring = new ComponentWiring<>(model, OrphanBuffer.class, directScheduler("orphanBuffer"));
        orphanBufferWiring.bind(orphanBuffer);

        freezeCheckHolder = new FreezeCheckHolder();
        freezeCheckHolder.setFreezeCheckRef(i -> false);
        final ConsensusEngine consensusEngine =
                new DefaultConsensusEngine(platformContext, roster, selfId, freezeCheckHolder);

        consensusEngineWiring = new ComponentWiring<>(model, ConsensusEngine.class, directScheduler("consensusEngine"));
        consensusEngineWiring.bind(consensusEngine);

        final ComponentWiring<EventWindowManager, EventWindow> eventWindowManagerWiring =
                new ComponentWiring<>(model, EventWindowManager.class, directScheduler("eventWindowManager"));
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
        consensusRoundOutputWire.solderTo("consensusOutputTestTool", "round output", output::consensusRound);

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

    /**
     * @return the freeze check holder
     */
    public @NonNull FreezeCheckHolder getFreezeCheckHolder() {
        return freezeCheckHolder;
    }

    public void reset() {
        loadSnapshot(SyntheticSnapshot.getGenesisSnapshot());
        output.clear();
    }

    private void throwComponentExceptionsIfAny() {
        componentExceptions.stream().findFirst().ifPresent(t -> {
            throw new RuntimeException(t);
        });
    }

    public <X> TaskScheduler<X> directScheduler(final String name) {
        return model.<X>schedulerBuilder(name)
                .withType(TaskSchedulerType.DIRECT)
                // This is needed because of the catch in StandardOutputWire.forward()
                // if we throw the exception, it will be caught by it and will not fail the test
                .withUncaughtExceptionHandler((t, e) -> componentExceptions.add(e))
                .build();
    }
}
