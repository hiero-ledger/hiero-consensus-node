// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.graph;

import static com.swirlds.platform.test.fixtures.event.EventImplTestUtils.createEventImpl;

import com.swirlds.platform.internal.EventImpl;
import com.swirlds.platform.test.fixtures.event.TestingEventBuilder;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;

public class SimpleGraphs {
    /**
     * Builds graph below:
     *
     * <pre>
     * 3  4
     * | /|
     * 2  |
     * | \|
     * 0  1
     * </pre>
     */
    public static List<PlatformEvent> graph5e2n(final Random random) {
        final PlatformEvent e0 =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(1)).build();
        final PlatformEvent e1 =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(2)).build();
        final PlatformEvent e2 = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(1))
                .setSelfParent(e0)
                .setOtherParent(e1)
                .build();
        final PlatformEvent e3 = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(1))
                .setSelfParent(e2)
                .build();
        final PlatformEvent e4 = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(2))
                .setSelfParent(e1)
                .setOtherParent(e2)
                .build();
        System.out.println("e0 " + e0.getDescriptor());
        System.out.println("e1 " + e1.getDescriptor());
        System.out.println("e2 " + e2.getDescriptor());
        System.out.println("e3 " + e3.getDescriptor());
        System.out.println("e4 " + e4.getDescriptor());
        return List.of(e0, e1, e2, e3, e4);
    }

    /**
     * Builds the graph below:
     *
     * <pre>
     *       8
     *     / |
     * 5  6  7
     * | /| /|
     * 3  | |4
     * | \|/ |
     * 0  1  2
     *
     * Consensus events: 0,1
     *
     * </pre>
     */
    public static List<EventImpl> graph9e3n(final Random random) {
        // generation 0
        final EventImpl e0 = createEventImpl(
                new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(1))
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.680Z")),
                null,
                null);
        e0.setConsensus(true);

        final EventImpl e1 = createEventImpl(
                new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(2))
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.681Z")),
                null,
                null);
        e1.setConsensus(true);

        final EventImpl e2 = createEventImpl(
                new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(3))
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.682Z")),
                null,
                null);

        // generation 1
        final EventImpl e3 = createEventImpl(
                new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(1))
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.683Z")),
                e0,
                e1);

        final EventImpl e4 = createEventImpl(
                new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(3))
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.686Z")),
                e2,
                null);

        // generation 2
        final EventImpl e5 = createEventImpl(
                new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(1))
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.685Z")),
                e3,
                null);

        final EventImpl e6 = createEventImpl(
                new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(2))
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.686Z")),
                e1,
                e3);

        final EventImpl e7 = createEventImpl(
                new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(3))
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.690Z")),
                e4,
                e1);

        // generation 3
        final EventImpl e8 = createEventImpl(
                new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(3))
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.694Z")),
                e7,
                e6);

        return List.of(e0, e1, e2, e3, e4, e5, e6, e7, e8);
    }
}
