// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.network.simulation;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.hiero.consensus.hashgraph.impl.ConsensusEngineOutput;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

class SimulationStats {
    private final List<Duration> c2cs = new ArrayList<>();
    private long numEvents = 0;

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

    void print(final Duration tick, final int nodes, final Duration timePassed) {
        final double averageC2C = c2cs.stream().mapToLong(Duration::toNanos).average().orElse(0);
        final Duration max = c2cs.stream().max(Comparator.naturalOrder()).orElse(Duration.ZERO);
        System.out.println("Delay(μs)  Nodes avgC2C(μs) maxC2C(μs)  ev/sec");
        System.out.printf("%,9d %6d %,10d %,10d %,7d %n",
                tick.toNanos() / 1000,
                nodes,
                (long) averageC2C / 1000,
                toMicros(max),
                (long) (numEvents / ((double) timePassed.toMillis() / 1000)));
    }

    private static long toMicros(final Duration d) {
        return d.getSeconds() * 1_000_000L + d.getNano() / 1_000L;
    }
}
