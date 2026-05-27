// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.hiero.consensus.hashgraph.impl.ConsensusEngineOutput;

/**
 * Accumulates and reports performance statistics gathered during a network simulation run.
 */
class SimulationStats {
    private final List<Duration> c2cs = new ArrayList<>();
    private long numEvents = 0;

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
                .map(cr -> cr.getConsensusEvents().stream()
                        .map(ce -> Duration.between(ce.getTimeCreated(), cr.getReachedConsTimestamp()))
                        .toList())
                .flatMap(List::stream)
                .forEach(c2cs::add);
    }

    /**
     * Prints a summary of the collected statistics to standard output.
     *
     * @param nodes       the number of nodes in the simulated network
     * @param timePassed  the total simulated time that elapsed during the run
     */
    void print(final int nodes, final Duration timePassed) {
        final double averageC2C =
                c2cs.stream().mapToLong(Duration::toNanos).average().orElse(0);
        final Duration max = c2cs.stream().max(Comparator.naturalOrder()).orElse(Duration.ZERO);
        System.out.printf("Num nodes: %d%n", nodes);
        System.out.printf("Avg C2C:   %s%n", Duration.ofNanos((long) averageC2C));
        System.out.printf("Max C2C:   %s%n", max);
        System.out.printf("Ev/sec:    %,d%n", (long) (numEvents / ((double) timePassed.toMillis() / 1000)));
    }
}
