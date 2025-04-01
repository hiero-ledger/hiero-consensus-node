// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.consensus.TestIntake;
import com.swirlds.platform.test.fixtures.consensus.framework.validation.RoundInternalEqualityValidation;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.EventSource;
import com.swirlds.platform.test.fixtures.event.source.StandardEventSource;
import com.swirlds.platform.test.fixtures.graph.OtherParentMatrixFactory;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.consensus.model.event.EventConstants;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.junit.jupiter.api.Test;

class IntakeAndConsensusTests {
    /**
     * Reproduces #5635
     * <p>
     * This test creates a graph with two partitions, where one partition is small enough that it is not needed for
     * consensus. Because the small partition does not affect consensus, we can delay inserting those events and still
     * reach consensus. We delay adding the small partition events until the first of these events becomes ancient. This
     * would lead to at least one subsequent small partition event being non-ancient, but not having only ancient
     * parents. We then insert the small partition events into 2 Node objects that have different consensus states. In
     * one node, these small partition parents are ancient, in the other they are not. We then stop partitioning, so
     * that new events will be descendants of some small partition events. This means that the small partition events
     * will now be needed for consensus. If the small partition events are not inserted into one of the nodes correctly,
     * it will not be able to reach consensus.
     * <p>
     * Tests the workaround described in #5762
     */
    @Test
    void nonAncientEventWithMissingParents() {
        final long seed = 0;
        final int numNodes = 10;
        final List<Integer> partitionNodes = List.of(0, 1);

        final Configuration configuration = new TestConfigBuilder()
                .withValue(ConsensusConfig_.ROUNDS_NON_ANCIENT, 25)
                .withValue(ConsensusConfig_.ROUNDS_EXPIRED, 25)
                .getOrCreateConfig();

        final PlatformContext platformContext = TestPlatformContextBuilder.create()
                .withConfiguration(configuration)
                .build();

        // the generated events are first fed into consensus so that round created is calculated before we start
        // using them
        final List<EventSource> eventSources = Stream.generate(StandardEventSource::new)
                .map(ses -> (EventSource) ses)
                .limit(numNodes)
                .toList();
        final StandardGraphGenerator generator = new StandardGraphGenerator(platformContext, seed, eventSources);
        final TestIntake node1 = new TestIntake(platformContext, generator.getRoster());
        final TestIntake node2 = new TestIntake(platformContext, generator.getRoster());

        // first we generate events regularly, until we have some ancient rounds
        final int firstBatchSize = 5000;
        List<EventImpl> batch = generator.generateEvents(firstBatchSize);
        for (final EventImpl event : batch) {
            node1.addEvent(event.getBaseEvent().copyGossipedData());
            node2.addEvent(event.getBaseEvent().copyGossipedData());
        }

        assertConsensusEvents(node1, node2);

        // now we create a partition
        generator.setOtherParentAffinity(
                OtherParentMatrixFactory.createPartitionedOtherParentAffinityMatrix(numNodes, partitionNodes));

        // during the partition, we will not insert the minority partition events into consensus
        // we generate just enough events to make the first event of the partition ancient, but we don't insert the
        // last event into the second consensus
        long partitionMinGen = EventConstants.GENERATION_UNDEFINED;
        long partitionMaxGen = EventConstants.GENERATION_UNDEFINED;
        final List<EventImpl> partitionedEvents = new LinkedList<>();
        boolean succeeded = false;
        EventImpl lastEvent = null;
        while (!succeeded) {
            batch = generator.generateEvents(1);
            lastEvent = batch.getFirst();
            if (partitionNodes.contains((int) lastEvent.getCreatorId().id())) {
                partitionMinGen = partitionMinGen == EventConstants.GENERATION_UNDEFINED
                        ? lastEvent.getGeneration()
                        : Math.min(partitionMinGen, lastEvent.getGeneration());
                partitionMaxGen = Math.max(partitionMaxGen, lastEvent.getGeneration());
                partitionedEvents.add(lastEvent);
            } else {
                node1.addEvent(lastEvent.getBaseEvent().copyGossipedData());
                final long node1NonAncGen = node1.getOutput().getEventWindow().getAncientThreshold();
                if (partitionMaxGen > node1NonAncGen && partitionMinGen < node1NonAncGen) {
                    succeeded = true;
                } else {
                    node2.addEvent(lastEvent.getBaseEvent().copyGossipedData());
                }
            }
        }

        // now we insert the minority partition events into both consensus objects, which are in a different state of
        // consensus
        for (final EventImpl partitionedEvent : partitionedEvents) {
            node1.addEvent(partitionedEvent.getBaseEvent().copyGossipedData());
            node2.addEvent(partitionedEvent.getBaseEvent().copyGossipedData());
        }
        // now we add the event that was added to 1 but not to 2
        node2.addEvent(lastEvent.getBaseEvent());
        final long consRoundBeforeLastBatch =
                node1.getConsensusRounds().getLast().getRoundNum();
        assertConsensusEvents(node1, node2);

        // now the partitions rejoin
        generator.setOtherParentAffinity(OtherParentMatrixFactory.createBalancedOtherParentMatrix(numNodes));

        // now we generate more events and expect consensus to be the same
        final int secondBatchSize = 1000;
        batch = generator.generateEvents(secondBatchSize);
        for (final EventImpl event : batch) {
            node1.addEvent(event.getBaseEvent());
            node2.addEvent(event.getBaseEvent());
        }
        assertThat(node1.getConsensusRounds().getLast().getRoundNum())
                .isGreaterThan(consRoundBeforeLastBatch)
                .withFailMessage("consensus did not advance after the partition rejoined");
        assertConsensusEvents(node1, node2);
    }

    private static void assertConsensusEvents(final TestIntake node1, final TestIntake node2) {
        final RoundInternalEqualityValidation roundInternalEqualityValidation = new RoundInternalEqualityValidation();

        final Iterator<ConsensusRound> iterator1 = node1.getConsensusRounds().iterator();
        final Iterator<ConsensusRound> iterator2 = node2.getConsensusRounds().iterator();
        while (iterator1.hasNext() && iterator2.hasNext()) {
            roundInternalEqualityValidation.validate(iterator1.next(), iterator2.next());
        }
        node1.getConsensusRounds().clear();
        node2.getConsensusRounds().clear();
    }
}
