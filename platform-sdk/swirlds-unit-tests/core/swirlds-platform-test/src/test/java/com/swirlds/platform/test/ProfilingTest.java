// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.WeightGenerators;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.Consensus;
import com.swirlds.platform.ConsensusImpl;
import com.swirlds.platform.metrics.NoOpConsensusMetrics;
import com.swirlds.platform.roster.RosterRetriever;
import com.swirlds.platform.system.address.AddressBook;
import com.swirlds.platform.test.event.emitter.StandardEventEmitter;
import com.swirlds.platform.test.event.source.EventSourceFactory;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.EventSource;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

public class ProfilingTest {
    /**
     * Attaching a profiler to a JMH benchmark does not seem to work. This is a test use to profile consensus.
     */
    @Test
    @Disabled
    void profile() {
        final long seed = 0;
        final int numNodes = 39;
        final int numEvents = 250_000;

        final List<EventSource> eventSources =
                EventSourceFactory.newStandardEventSources(WeightGenerators.balancedNodeWeights(numNodes));

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final StandardGraphGenerator generator = new StandardGraphGenerator(platformContext, seed, eventSources);
        final StandardEventEmitter emitter = new StandardEventEmitter(generator);
        final AddressBook addressBook = emitter.getGraphGenerator().getAddressBook();

        final Consensus consensus = new ConsensusImpl(
                platformContext, new NoOpConsensusMetrics(), RosterRetriever.buildRoster(addressBook));

        for (int i = 0; i < numEvents; i++) {
            consensus.addEvent(emitter.emitEvent());
        }
    }
}
