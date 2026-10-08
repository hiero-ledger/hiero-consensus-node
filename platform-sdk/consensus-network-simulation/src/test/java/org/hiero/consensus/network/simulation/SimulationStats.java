// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation;

import com.hedera.hapi.platform.event.GossipEvent;
import java.time.Duration;
import java.util.List;
import org.hiero.consensus.benchmark.tools.histogram.LatencyHistogram;
import org.hiero.consensus.hashgraph.impl.ConsensusEngineOutput;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Accumulates and reports performance statistics gathered during a network simulation run.
 *
 * <p>The creation-to-consensus latency (C2C) of every event is recorded in a {@link LatencyHistogram}, which needs a
 * fixed amount of memory however many events the simulation produces.
 */
class SimulationStats {

    /** The number of precision bits of the histogram: buckets at most 2^-10 (about 0.1%) of their values wide. */
    private static final int PRECISION_BITS = 10;

    /** The number of range bits of the histogram: latencies up to 2^36 - 1 ns (about 68.7 s) are tracked. */
    private static final int RANGE_BITS = 36;

    private final LatencyHistogram c2c = new LatencyHistogram(PRECISION_BITS, RANGE_BITS);
    private long numEvents = 0;
    private long numBytes = 0;

    /**
     * Records statistics from a batch of consensus engine outputs produced during a single simulation tick.
     *
     * @param engineOutputs the outputs returned by the consensus engine for each event processed in the tick
     */
    void record(final List<ConsensusEngineOutput> engineOutputs) {
        numEvents += engineOutputs.stream()
                .map(ConsensusEngineOutput::preConsensusEvents)
                .mapToLong(List::size)
                .sum();
        engineOutputs.stream()
                .map(ConsensusEngineOutput::consensusRounds)
                .flatMap(List::stream)
                .forEach(this::recordRound);
    }

    /**
     * Records the C2C of every event of a consensus round.
     *
     * @param round the consensus round
     */
    private void recordRound(final ConsensusRound round) {
        for (final PlatformEvent event : round.getConsensusEvents()) {
            c2c.record(Duration.between(event.getTimeCreated(), round.getReachedConsTimestamp())
                    .toNanos());
        }
    }

    public void records(final List<PlatformEvent> events) {
        numBytes += events.stream()
                .mapToLong(ce -> GossipEvent.PROTOBUF.measureRecord(ce.getGossipEvent()))
                .sum();
    }

    /**
     * Prints a summary of the collected statistics to standard output.
     *
     * @param nodes      the number of nodes in the simulated network
     * @param timePassed the total simulated time that elapsed during the run
     */
    SimulationResult print(final int nodes, final Duration timePassed) {
        final SimulationResult result =
                new SimulationResult(nodes, c2c, (long) (numEvents / ((double) timePassed.toMillis() / 1000)), (long)
                        (numBytes / ((double) timePassed.toMillis() / 1000)));
        System.out.println(result);
        return result;
    }
}
