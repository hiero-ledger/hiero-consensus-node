// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static org.hiero.consensus.wiring.framework.wires.SolderType.INJECT;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.hiero.consensus.crypto.DefaultEventHasher;
import org.hiero.consensus.crypto.EventHasher;
import org.hiero.consensus.event.NoOpIntakeEventCounter;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.hashgraph.impl.DefaultHashgraphModule;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.orphan.DefaultOrphanBuffer;
import org.hiero.consensus.orphan.OrphanBuffer;
import org.hiero.consensus.wiring.framework.component.ComponentWiring;
import org.hiero.consensus.wiring.framework.model.DeterministicWiringModel;
import org.hiero.consensus.wiring.framework.model.WiringModelBuilder;
import org.hiero.consensus.wiring.framework.schedulers.TaskScheduler;
import org.hiero.consensus.wiring.framework.schedulers.builders.TaskSchedulerType;
import org.hiero.consensus.wiring.framework.wires.input.InputWire;

/**
 * The production hashgraph wiring, driven one event at a time, as the reference for {@code FlickerIntakePinningTest}.
 * <p>
 * This is deliberately not {@code TestIntake}. {@code TestIntake} hand-rolls its own wiring around
 * {@code DefaultConsensusEngine}, and nothing pins it to production, so pinning the Flicker harness to it would put an
 * unchecked approximation in the middle of the trust chain. {@link DefaultHashgraphModule} is the real thing: it lives
 * in this module's {@code src/main}, it reads the production {@code HashgraphWiringConfig}, and it builds the same
 * transformer and splitter chain the platform runs. Using it costs no new module dependency.
 * <p>
 * The soldering below mirrors {@code ConsensusLayerWiring}: the orphan buffer's split output feeds
 * {@link DefaultHashgraphModule#eventInputWire()}, and the module's consensus round output feeds back as the orphan
 * buffer's event window.
 * <p>
 * <b>What is deliberately left out, and why.</b> Production puts four more components between the hasher and the orphan
 * buffer — the internal event validator, the deduplicator, the signature validator, and the branch detector. All four
 * are <i>admission control</i>: they decide which events reach consensus, not what consensus computes from the events
 * it gets. Since both sides of the pinning test are handed the same pre-built, topologically ordered graph, none of
 * them can be a source of divergence.
 * <p>
 * They are also unusable here. {@code DefaultEventSignatureValidator} performs a real
 * {@code signatureVerifier.verifySignature(...)}, while {@code TestingEventBuilder} fills the signature with random
 * bytes, so every fixture event would be rejected. Admitting them would mean generating a keyed roster and signing
 * each event — which buys coverage of admission control, an explicit non-goal of this suite.
 */
final class ProductionHashgraphIntake {

    private final DeterministicWiringModel model;
    private final InputWire<PlatformEvent> unhashedEventInput;
    private final DefaultHashgraphModule hashgraphModule;

    private final List<ConsensusRound> consensusRounds = new ArrayList<>();
    private final List<Throwable> componentExceptions = new ArrayList<>();

    ProductionHashgraphIntake(@NonNull final Configuration configuration, @NonNull final RosterWrapper roster) {
        final Time time = Time.getCurrent();
        final Metrics metrics = new NoOpMetrics();
        model = WiringModelBuilder.create(new NoOpMetrics(), time)
                .deterministic()
                .build();

        final ComponentWiring<EventHasher, PlatformEvent> hasherWiring =
                new ComponentWiring<>(model, EventHasher.class, scheduler("eventHasher"));
        hasherWiring.bind(new DefaultEventHasher());

        final ComponentWiring<OrphanBuffer, List<PlatformEvent>> orphanBufferWiring =
                new ComponentWiring<>(model, OrphanBuffer.class, scheduler("orphanBuffer"));
        orphanBufferWiring.bind(new DefaultOrphanBuffer(metrics, new NoOpIntakeEventCounter()));

        hashgraphModule = new DefaultHashgraphModule();
        hashgraphModule.initialize(
                model, configuration, metrics, time, roster, NodeId.of(0), timestamp -> false, null, 0L);

        hasherWiring.getOutputWire().solderTo(orphanBufferWiring.getInputWire(OrphanBuffer::handleEvent));
        orphanBufferWiring.<PlatformEvent>getSplitOutput().solderTo(hashgraphModule.eventInputWire());

        hashgraphModule
                .consensusRoundOutputWire()
                .buildTransformer("EventWindowExtractor", "consensus round", ConsensusRound::getEventWindow)
                .solderTo(orphanBufferWiring.getInputWire(OrphanBuffer::setEventWindow), INJECT);
        hashgraphModule.consensusRoundOutputWire().solderTo("roundCollector", "consensus rounds", consensusRounds::add);

        // Input wires cannot be created once the model is started, so build the one the test drives up front.
        unhashedEventInput = hasherWiring.getInputWire(EventHasher::hashEvent);

        model.start();
    }

    /**
     * Feed one event, running the model to quiescence before returning.
     *
     * @param event the event to add
     */
    void addEvent(@NonNull final PlatformEvent event) {
        unhashedEventInput.put(event);
        model.doAllWork();
        componentExceptions.stream().findFirst().ifPresent(throwable -> {
            throw new RuntimeException(throwable);
        });
    }

    /**
     * @return every round that has reached consensus, in order
     */
    @NonNull
    List<ConsensusRound> getConsensusRounds() {
        return Collections.unmodifiableList(consensusRounds);
    }

    /**
     * A scheduler that surfaces component exceptions to the test. Without the handler,
     * {@code StandardOutputWire.forward()} swallows them and the test passes on a broken run.
     */
    private <X> TaskScheduler<X> scheduler(@NonNull final String name) {
        return model.<X>schedulerBuilder(name)
                .withType(TaskSchedulerType.SEQUENTIAL)
                .withUncaughtExceptionHandler((thread, throwable) -> componentExceptions.add(throwable))
                .build();
    }
}
