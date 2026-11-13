// SPDX-License-Identifier: Apache-2.0
package org.hiero.otter.fixtures.falcon.latency;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.otter.fixtures.falcon.FalconNetwork;
import org.hiero.otter.fixtures.falcon.FalconObserver;
import org.hiero.otter.fixtures.network.simulation.NetworkObserver;
import org.hiero.otter.fixtures.network.simulation.NetworkView;
import org.hiero.otter.fixtures.network.simulation.SimulatedNetworkConnectivity;
import org.hiero.otter.fixtures.network.simulation.duplicates.DuplicateTracking;

/**
 * Measures the creation-to-consensus latency (C2C) and the event throughput of a Falcon network, in simulated time, and
 * optionally estimates the duplicate copies of events that production gossip would send to its full nodes.
 *
 * <p>The measurement follows the network as its {@link FalconObserver}, so it must be set with
 * {@link FalconNetwork#observe(FalconObserver)} before the nodes are added:
 * <pre>{@code
 * final ConsensusLatencyMeasurement measurement = ConsensusLatencyMeasurement.create();
 * network.observe(measurement);
 * // add the nodes, start the network
 * measurement.restart(timeManager.now());
 * // let the network run
 * final ConsensusLatencyResult result = measurement.snapshot(timeManager.now());
 * }</pre>
 *
 * <p>The C2C is observed by the full nodes only, because mini-nodes run no consensus engine, and so are the duplicates;
 * the throughput covers all nodes, whose created events are counted as they submit them to the simulated network. The duplicates are estimated by replaying the syncs of production gossip, see
 * {@link DuplicateTracking}, which makes a run noticeably slower, so they are only estimated by a measurement created
 * with {@link #createWithDuplicates()}, and reported as zero otherwise.
 *
 * <p>This class is not thread-safe; Falcon drives all nodes from a single thread.
 */
public final class ConsensusLatencyMeasurement implements FalconObserver {

    /** Collects what is measured. */
    private final ConsensusLatencyRecorder recorder = new ConsensusLatencyRecorder();

    /** Whether the duplicates are estimated. */
    private final boolean estimateDuplicates;

    private ConsensusLatencyMeasurement(final boolean estimateDuplicates) {
        this.estimateDuplicates = estimateDuplicates;
    }

    /**
     * Creates a measurement of the C2C and the throughput, which reports no duplicates.
     *
     * @return the measurement
     */
    @NonNull
    public static ConsensusLatencyMeasurement create() {
        return new ConsensusLatencyMeasurement(false);
    }

    /**
     * Creates a measurement of the C2C and the throughput that also estimates the duplicates production gossip would
     * send to the full nodes.
     *
     * @return the measurement
     */
    @NonNull
    public static ConsensusLatencyMeasurement createWithDuplicates() {
        return new ConsensusLatencyMeasurement(true);
    }

    /**
     * Discards everything measured so far and starts a new measurement period now. Events created before now are left
     * out of the C2C even if they reach consensus later. Typically called right after the network has started, to leave
     * out its start-up, during which the mini-nodes already create events but the full nodes do not run consensus yet.
     *
     * @param now the current simulated time
     * @throws IllegalStateException if the network has not been started yet
     */
    public void restart(@NonNull final Instant now) {
        recorder.restart(now);
    }

    /**
     * Returns what has been measured from the start of the measurement period until now.
     *
     * @param now the current simulated time, which ends the measurement period
     * @return a snapshot of the measurement, which later measurements do not change
     * @throws IllegalStateException if the network has not been started yet
     */
    @NonNull
    public ConsensusLatencyResult snapshot(@NonNull final Instant now) {
        return recorder.snapshot(now);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void nodeAdded(@NonNull final NodeId nodeId, final boolean fullNode) {
        recorder.registerNode(nodeId, fullNode);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Starts the measurement, follows the network to count the events the nodes create, and starts estimating the
     * duplicates sent to the full nodes, if they are estimated.
     */
    @Override
    public void networkStarting(@NonNull final SimulatedNetworkConnectivity network, @NonNull final Instant now) {
        recorder.markStarted(now);
        network.addObserver(new CreatedEvents(network));
        if (estimateDuplicates) {
            network.addObserver(
                    DuplicateTracking.estimator(network, recorder.fullNodes(), recorder::onEstimatedDuplicates));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void consensusRound(@NonNull final NodeId nodeId, @NonNull final ConsensusRound round) {
        recorder.onConsensusRound(nodeId, round);
    }

    /**
     * Counts the events the nodes create. Every node, full node and mini-node alike, submits each event it creates to
     * the simulated network once, which is when it is counted.
     */
    private final class CreatedEvents implements NetworkObserver {

        /** The network, which tells the rank of the creator of an event. */
        private final NetworkView network;

        /**
         * Constructor.
         *
         * @param network the network
         */
        private CreatedEvents(@NonNull final NetworkView network) {
            this.network = requireNonNull(network);
        }

        /**
         * {@inheritDoc}
         *
         * <p>Counts the event if it is submitted by its creator.
         */
        @Override
        public void eventSubmitted(@NonNull final PlatformEvent event, final int submitter, final long now) {
            if (submitter == network.rankOf(event.getCreatorId())) {
                recorder.onEventCreated(event);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void scheduled(final long now) {
            // nothing to do
        }
    }
}
