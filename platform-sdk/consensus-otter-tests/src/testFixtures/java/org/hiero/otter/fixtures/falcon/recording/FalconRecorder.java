// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon.recording;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.assertj.core.data.Percentage;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.event.creator.config.EventCreationWiringConfig_;
import org.hiero.consensus.event.intake.config.EventIntakeWiringConfig_;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.test.fixtures.io.RealisticPingSamples;
import org.hiero.otter.fixtures.Node;
import org.hiero.otter.fixtures.TimeManager;
import org.hiero.otter.fixtures.falcon.FalconNetwork;
import org.hiero.otter.fixtures.falcon.FalconNode;
import org.hiero.otter.fixtures.falcon.FalconTestEnvironment;
import org.hiero.otter.fixtures.falcon.recording.Step.Received;
import org.hiero.otter.fixtures.falcon.recording.Step.Released;
import org.hiero.otter.fixtures.network.BandwidthLimit;
import org.hiero.otter.fixtures.network.PredefinedPingTopologyConfiguration;

/**
 * Runs a seeded Falcon network and records what some of its nodes see, in order, with simulated time.
 *
 * <p>The network consists of {@link RecordingSpec#recordedNodes()} full nodes, which are recorded, and mini-nodes for
 * the rest, on the mainnet ping matrix ({@link RealisticPingSamples#MAINNET}). On the recorded nodes, the event
 * deduplicator, the orphan buffer, and the event creation manager run on direct schedulers, so that passing an event
 * between them takes no simulated time. The consensus engine keeps its scheduler so that the event creator gets each
 * event before the event window it causes, as in production; the window comes one tick after the event.
 *
 * <p>The network runs until every recorded node has released {@link RecordingSpec#events()} events. It fails if the
 * recorded node with the fewest released events releases none for {@link #STALL_TIMEOUT} of simulated time.
 */
public final class FalconRecorder {

    /** The simulated time after which a network that releases no event counts as stalled. */
    public static final Duration STALL_TIMEOUT = Duration.ofMinutes(1);

    /** The jitter of the network links, the default of the predefined ping topology. */
    private static final Percentage JITTER = Percentage.withPercentage(10);

    /** The scheduler type of the components that must not delay events. */
    private static final String DIRECT = "DIRECT";

    private FalconRecorder() {}

    /**
     * Runs the network the spec describes and returns what its recorded nodes saw.
     *
     * @param spec what to simulate
     * @return the recording
     */
    @NonNull
    public static Recording record(@NonNull final RecordingSpec spec) {
        requireNonNull(spec);
        final FalconTestEnvironment env = new FalconTestEnvironment(spec.seed(), spec.tick());
        try {
            final FalconNetwork network = env.network();
            network.topology(new PredefinedPingTopologyConfiguration(
                    RealisticPingSamples.MAINNET, JITTER, BandwidthLimit.UNLIMITED_BANDWIDTH));
            network.transactions(spec.shape());
            if (spec.signingSchema() != null) {
                network.signingSchema(spec.signingSchema());
            }

            final List<Node> recordedNodes = network.addNodes(spec.recordedNodes());
            if (spec.numNodes() > spec.recordedNodes()) {
                network.addMiniNodes(spec.numNodes() - spec.recordedNodes());
            }
            final List<StepCollector> collectors = new ArrayList<>(recordedNodes.size());
            for (final Node node : recordedNodes) {
                node.configuration()
                        .withConfigValue(EventIntakeWiringConfig_.EVENT_DEDUPLICATOR, DIRECT)
                        .withConfigValue(EventIntakeWiringConfig_.ORPHAN_BUFFER, DIRECT)
                        .withConfigValue(EventCreationWiringConfig_.EVENT_CREATION_MANAGER, DIRECT);
                final StepCollector collector = new StepCollector();
                ((FalconNode) node).stepRecorder(collector);
                collectors.add(collector);
            }

            network.start();
            runUntilReleased(env.timeManager(), collectors, spec.events());

            final List<NodeRecording> nodeRecordings = new ArrayList<>(recordedNodes.size());
            for (int i = 0; i < recordedNodes.size(); i++) {
                final FalconNode node = (FalconNode) recordedNodes.get(i);
                nodeRecordings.add(new NodeRecording(
                        node.selfId(),
                        node.configuration().current(),
                        node.creatorStartTime(),
                        node.creatorSeed(),
                        collectors.get(i).stepsUntilReleased(spec.events())));
            }
            return new Recording(spec, RosterWrapper.of(network.roster()), network.keysAndCerts(), nodeRecordings);
        } finally {
            env.destroy();
        }
    }

    /**
     * Ticks until every collector has seen the given number of released events. Waits in rounds, each until the
     * collector with the fewest released events sees another one, so that only a stalled network fails, not a long
     * recording.
     *
     * @throws org.hiero.otter.fixtures.exceptions.TimeoutException if the collector with the fewest released events
     * sees no new one for {@link #STALL_TIMEOUT}
     */
    private static void runUntilReleased(
            @NonNull final TimeManager timeManager, @NonNull final List<StepCollector> collectors, final int events) {
        while (minReleased(collectors) < events) {
            final int releasedBefore = minReleased(collectors);
            timeManager.waitForCondition(
                    () -> minReleased(collectors) > releasedBefore,
                    STALL_TIMEOUT,
                    "A recorded node released no event for " + STALL_TIMEOUT);
        }
    }

    private static int minReleased(@NonNull final List<StepCollector> collectors) {
        int min = Integer.MAX_VALUE;
        for (final StepCollector collector : collectors) {
            min = Math.min(min, collector.released);
        }
        return min;
    }

    /** Collects the steps of one recorded node and counts its released events. */
    private static final class StepCollector implements Consumer<Step> {

        private final List<Step> steps = new ArrayList<>();

        /**
         * The hashes of the events received so far. A node receives each event from every node that passes it on;
         * only the first copy is kept. Remove once the simulated network sends only the copies that can arrive first
         * (#27782).
         */
        private final Set<Hash> received = new HashSet<>();

        private int released;

        @Override
        public void accept(@NonNull final Step step) {
            if (step instanceof final Received r && !received.add(r.event().getHash())) {
                return;
            }
            if (step instanceof Released) {
                released++;
            }
            steps.add(step);
        }

        /**
         * Returns the steps up to and including the one that releases the given number of events.
         */
        @NonNull
        List<Step> stepsUntilReleased(final int events) {
            int count = 0;
            for (int i = 0; i < steps.size(); i++) {
                if (steps.get(i) instanceof Released && ++count == events) {
                    return steps.subList(0, i + 1);
                }
            }
            throw new IllegalStateException("Only " + count + " of " + events + " events were released");
        }
    }
}
