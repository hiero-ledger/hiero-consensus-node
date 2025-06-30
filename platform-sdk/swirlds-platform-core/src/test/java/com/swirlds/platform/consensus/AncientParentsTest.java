// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.consensus;

import static org.assertj.core.api.Assertions.assertThat;

import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.consensus.TestIntake;
import com.swirlds.platform.test.fixtures.consensus.framework.validation.ConsensusRoundValidator;
import com.swirlds.platform.test.fixtures.event.generator.StandardGraphGenerator;
import com.swirlds.platform.test.fixtures.event.source.EventSource;
import com.swirlds.platform.test.fixtures.event.source.StandardEventSource;
import com.swirlds.platform.test.fixtures.graph.OtherParentMatrixFactory;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.junit.jupiter.api.Test;

class AncientParentsTest {

    public static final int FIRST_BATCH_SIZE = 5000;
    public static final int SECOND_BATCH_SIZE = 1000;

    /**
     * Tests the scenario where some stale events are added to different nodes at different points in consensus time.
     * Depending on the point in consensus time, an event might have ancient parents on one node, but not on the other.
     * Both nodes should have the same consensus output.
     * <p>
     * This test creates a graph with two partitions, where one partition is small enough that it is not required for
     * consensus. Because the small partition does not affect consensus, we can delay inserting those events and still
     * reach consensus. We delay adding the small partition events until the first of these events becomes ancient. This
     * would lead to at least one subsequent small partition event being non-ancient, but not having only ancient
     * parents. We then insert the small partition events into 2 Node objects that have different consensus states. In
     * one node, these small partition parents are ancient, in the other they are not. We then stop partitioning, so
     * that new events will be descendants of some small partition events. This means that the small partition events
     * will now be needed for consensus. If the small partition events are not inserted into one of the nodes correctly,
     * it will not be able to reach consensus.
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

        final List<EventSource> eventSources = Stream.generate(StandardEventSource::new)
                .map(ses -> (EventSource) ses)
                .limit(numNodes)
                .toList();
        final StandardGraphGenerator generator = new StandardGraphGenerator(platformContext, seed, eventSources);
        final TestIntake node1 = new TestIntake(platformContext, generator.getRoster());
        final TestIntake node2 = new TestIntake(platformContext, generator.getRoster());

        // first, we generate events regularly, until we have some ancient rounds
        for (final EventImpl event : generator.generateEvents(FIRST_BATCH_SIZE)) {
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
        final List<EventImpl> partitionedEvents = new LinkedList<>();
        boolean succeeded = false;
        EventImpl lastEvent = null;
        EventImpl firstEventInPartition = null;
        while (!succeeded) {
            lastEvent = generator.generateEvents(1).getFirst();
            if (partitionNodes.contains((int) lastEvent.getCreatorId().id())) {
                // we have generated an event in the minority partition

                if (firstEventInPartition == null) {
                    // this is the first event in the partition
                    firstEventInPartition = lastEvent;
                }

                // we don't add these events to consensus yet, we will add them later
                partitionedEvents.add(lastEvent);
            } else {
                // this is an event in the majority partition
                // we add it to node 1 always
                node1.addEvent(lastEvent.getBaseEvent().copyGossipedData());

                // if this event caused the first event in the partition to become ancient, then we exit this loop.
                // we will add this event to node 2 later, after we add the partitioned events
                final EventWindow node1Window = node1.getOutput().getEventWindow();
                if (firstEventInPartition != null && node1Window.isAncient(firstEventInPartition.getBaseEvent())) {
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
        node2.addEvent(lastEvent.getBaseEvent().copyGossipedData());
        final long consRoundBeforeLastBatch =
                node1.getConsensusRounds().getLast().getRoundNum();
        // we wanted the first event in the partition to become ancient, so it should never reach consensus
        assertEventDidNotReachConsensus(firstEventInPartition, node1, node2);
        assertConsensusEvents(node1, node2);

        // now the partitions rejoin
        generator.setOtherParentAffinity(OtherParentMatrixFactory.createBalancedOtherParentMatrix(numNodes));

        // now we generate more events and expect consensus to be the same
        for (final EventImpl event : generator.generateEvents(SECOND_BATCH_SIZE)) {
            node1.addEvent(event.getBaseEvent().copyGossipedData());
            node2.addEvent(event.getBaseEvent().copyGossipedData());
        }
        assertThat(node1.getConsensusRounds().getLast().getRoundNum())
                .withFailMessage("consensus did not advance after the partition rejoined")
                .isGreaterThan(consRoundBeforeLastBatch);
        assertEventDidNotReachConsensus(firstEventInPartition, node1, node2);
        assertConsensusEvents(node1, node2);
    }

    /**
     * Assert that the supplied event did not reach consensus in any of the nodes.
     *
     * @param event the event to check
     * @param nodes the nodes to check
     */
    private static void assertEventDidNotReachConsensus(final EventImpl event, final TestIntake... nodes) {
        final Hash eventHash = event.getBaseHash();
        final boolean found = Arrays.stream(nodes)
                .map(TestIntake::getConsensusRounds)
                .flatMap(List::stream)
                .map(ConsensusRound::getConsensusEvents)
                .flatMap(List::stream)
                .map(PlatformEvent::getHash)
                .anyMatch(eventHash::equals);
        assertThat(found)
                .withFailMessage("Event was not supposed to reach consensus, but it did")
                .isFalse();
    }

    private static void assertConsensusEvents(final TestIntake node1, final TestIntake node2) {
        ConsensusRoundValidator.validate(node1.getConsensusRounds(), node2.getConsensusRounds());
        node1.getConsensusRounds().clear();
        node2.getConsensusRounds().clear();
    }
}
