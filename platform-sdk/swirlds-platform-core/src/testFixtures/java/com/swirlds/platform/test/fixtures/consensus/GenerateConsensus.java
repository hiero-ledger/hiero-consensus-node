// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.consensus;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.EventSource;
import com.swirlds.platform.test.fixtures.event.source.StandardEventSource;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import org.hiero.consensus.model.hashgraph.ConsensusRound;

/**
 * Test utility for generating consensus events
 */
public final class GenerateConsensus {
    private GenerateConsensus() {}

    /**
     * Generate consensus rounds
     *
     * @param numNodes
     * 		the number of nodes in the hypothetical network
     * @param numEvents
     * 		the number of pre-consensus events to generate
     * @param seed
     * 		the seed to use
     * @return consensus rounds
     */
    public static Deque<ConsensusRound> generateConsensusRounds(
            @NonNull PlatformContext platformContext, final int numNodes, final int numEvents, final long seed) {
        Objects.requireNonNull(platformContext);
        final List<EventSource> eventSources = new ArrayList<>();
        IntStream.range(0, numNodes).forEach(i -> eventSources.add(new StandardEventSource(false)));
        final StandardGraphGenerator generator = new StandardGraphGenerator(platformContext, seed, eventSources);
        final TestIntake intake = new TestIntake(platformContext, generator.getRoster());

        // generate events and feed them to consensus
        for (int i = 0; i < numEvents; i++) {
            intake.addEvent(generator.generateEvent().getBaseEvent());
        }

        // return the rounds
        return intake.getConsensusRounds();
    }
}
