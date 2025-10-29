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
import com.swirlds.component.framework.wires.input.InputWire;
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
import java.util.ArrayDeque;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
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

        model = WiringModelBuilder.create(new NoOpMetrics(), Time.getCurrent())
                .deterministic()
                .build();

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

        wireOrphanBufferAndConsensusEngine(orphanBufferWiring, consensusEngineWiring);

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
     * This method wires the orphanBuffer output to consensusEngine's input.
     * It is done using binding a custom lambda that is solving an edge case that is particularly important when using direct task schedulers:
     *  In a direct scheduler pipeline, when the orphan buffer releases an event to the consensus component, consensus may output an EventWindow that feeds back to the same orphan buffer which might produce that the orphan buffer releases a second batch of events.
     *  Because it's a DIRECT scheduler (synchronous, single-threaded), this causes reentrancy - the new list starts processing immediately, interrupting the previous list iteration.
     *  This means a child event from the new list can be processed before its parent from the original list, breaking topological order.
     *
     *  This method solves this issue using a queue of events and a flag indicating that there is a current integration in progress.
     *  Only one iteration at the time will feed elements to consensus, allowing us to maintain the topological order.
     * @param orphanBufferWiring the orphan buffer wiring
     * @param consensusEngineWiring the consensus engine wiring
     */
    private static void wireOrphanBufferAndConsensusEngine(
            final ComponentWiring<OrphanBuffer, List<PlatformEvent>> orphanBufferWiring,
            final ComponentWiring<ConsensusEngine, ConsensusEngineOutput> consensusEngineWiring) {
        final Queue<List<PlatformEvent>> pendingEventsLists = new ArrayDeque<>();
        final AtomicBoolean isProcessing = new AtomicBoolean(false);
        final InputWire<PlatformEvent> consensusInputWire =
                consensusEngineWiring.getInputWire(ConsensusEngine::addEvent);

        orphanBufferWiring.getOutputWire().solderTo("splitOrphanBufferOutput", "list of events", list -> {
            pendingEventsLists.add(list);

            if (isProcessing.compareAndExchange(false, true)) {
                // If already processing, some other iteration in the stack will handle the newly added list
                return;
            }

            try {
                while (!pendingEventsLists.isEmpty()) {
                    final List<PlatformEvent> currentList = pendingEventsLists.poll();
                    for (final PlatformEvent t : currentList) {
                        consensusInputWire.inject(t);
                    }
                }
            } finally {
                isProcessing.set(false);
            }
        });
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

    public void stop() {
        // Important: the order of the lines within this function matters. Do not alter the order of these
        // lines without understanding the implications of doing so. Consult the wiring diagram when deciding
        // whether to change the order of these lines.

        hasherWiring.flush();
        orphanBufferWiring.flush();
        consensusEngineWiring.flush();
        model.stop();
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
