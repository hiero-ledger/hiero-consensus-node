// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.test.fixtures.consensus;

import com.swirlds.base.time.Time;
import com.swirlds.config.api.Configuration;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.IntStream;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.generator.StandardGraphGenerator;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.source.EventSource;
import org.hiero.consensus.hashgraph.impl.test.fixtures.event.source.StandardEventSource;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Test utility for generating consensus events
 */
public final class GenerateConsensus {
    private GenerateConsensus() {}

    /**
     * Generate consensus rounds
     *
     * @param configuration the configuration to use
     * @param metrics the metrics to use
     * @param time the time to use
     * @param numNodes the number of nodes in the hypothetical network
     * @param numEvents the number of pre-consensus events to generate
     * @param seed the seed to use
     * @return consensus rounds
     */
    public static Deque<ConsensusRound> generateConsensusRounds(
            @NonNull final Configuration configuration,
            @NonNull final Metrics metrics,
            @NonNull final Time time,
            final int numNodes,
            final int numEvents,
            final long seed) {
        final List<EventSource> eventSources = new ArrayList<>();
        IntStream.range(0, numNodes).forEach(i -> eventSources.add(new StandardEventSource(false)));
        final StandardGraphGenerator generator =
                new StandardGraphGenerator(configuration, metrics, time, seed, eventSources);
        final TestIntake intake = new TestIntake(configuration, metrics, time, generator.getRoster());

        // generate events and feed them to consensus
        for (int i = 0; i < numEvents; i++) {
            intake.addEvent(generator.generateEvent());
        }

        // return the rounds
        return intake.getConsensusRounds();
    }
}
