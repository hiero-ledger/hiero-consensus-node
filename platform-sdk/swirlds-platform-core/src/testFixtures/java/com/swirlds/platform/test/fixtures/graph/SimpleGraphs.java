// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.test.fixtures.graph;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;

public class SimpleGraphs {

    /**
     * Builds graph with multiple other-parents below:
     *
     * <pre>
     * 8   9   10  11
     * │ / │ X │ \ │
     * 4   5   6   7
     * │ / │ X │ \ │
     * 0   1   2   3
     * </pre>
     *
     */
    public static SimpleGraph mopGraph(@NonNull final Random random) {
        final PlatformEvent e0 =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(1)).build();
        final PlatformEvent e1 =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(2)).build();
        final PlatformEvent e2 =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(3)).build();
        final PlatformEvent e3 =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(4)).build();

        final PlatformEvent e4 =
                new TestingEventBuilder(random).setSelfParent(e0).build();
        final PlatformEvent e5 = new TestingEventBuilder(random)
                .setSelfParent(e1)
                .setOtherParents(List.of(e0, e2))
                .build();
        final PlatformEvent e6 = new TestingEventBuilder(random)
                .setSelfParent(e2)
                .setOtherParents(List.of(e1, e3))
                .build();
        final PlatformEvent e7 =
                new TestingEventBuilder(random).setSelfParent(e3).build();

        final PlatformEvent e8 =
                new TestingEventBuilder(random).setSelfParent(e4).build();
        final PlatformEvent e9 = new TestingEventBuilder(random)
                .setSelfParent(e5)
                .setOtherParents(List.of(e4, e6))
                .build();
        final PlatformEvent e10 = new TestingEventBuilder(random)
                .setSelfParent(e6)
                .setOtherParents(List.of(e5, e7))
                .build();
        final PlatformEvent e11 =
                new TestingEventBuilder(random).setSelfParent(e7).build();
        return new SimpleGraph(random, e0, e1, e2, e3, e4, e5, e6, e7, e8, e9, e10, e11);
    }

    /**
     * Builds graph below:
     *
     * <pre>
     * 3  4
     * | /|
     * 2  |  7
     * | \|  | \
     * 0  1  5  6
     * </pre>
     *
     * Note that this graph has two parts which are not connected to each other
     */
    public static SimpleGraph graph8e4n(final Random random) {
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
        final PlatformEvent e5 =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(3)).build();
        final PlatformEvent e6 =
                new TestingEventBuilder(random).setCreatorId(NodeId.of(4)).build();
        final PlatformEvent e7 = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(3))
                .setSelfParent(e5)
                .setOtherParent(e6)
                .build();
        return new SimpleGraph(random, e0, e1, e2, e3, e4, e5, e6, e7);
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
    public static SimpleGraph graph9e3n(final Random random) {
        // generation 0
        final PlatformEvent e0 = new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(1))
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.680Z"))
                .setConsensusOrder(1L)
                        .build();

        final PlatformEvent e1 = new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(2))
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.681Z"))
                .setConsensusOrder(2L)
                .build();

        final PlatformEvent e2 = new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(3))
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.682Z"))
                .build();

        // generation 1
        final PlatformEvent e3 = new TestingEventBuilder(random)
                .setCreatorId(NodeId.of(1))
                        .setSelfParent(e0)
                        .setOtherParent(e1)
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.683Z"))
                .build();

        final PlatformEvent e4 = new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(3))
                        .setSelfParent(e2)
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.686Z"))
                .build();

        // generation 2
        final PlatformEvent e5 =
                new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(1))
                        .setSelfParent(e3)
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.685Z"))
                        .build();

        final PlatformEvent e6 =
                new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(2))
                        .setSelfParent(e1)
                        .setOtherParent(e3)
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.686Z"))
                        .build();

        final PlatformEvent e7 =
                new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(3))
                        .setSelfParent(e4)
                        .setOtherParent(e1)
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.690Z"))
                        .build();

        // generation 3
        final PlatformEvent e8 =
                new TestingEventBuilder(random)
                        .setCreatorId(NodeId.of(3))
                        .setSelfParent(e7)
                        .setOtherParent(e6)
                        .setTimeCreated(Instant.parse("2020-05-06T13:21:56.694Z"))
                        .build();

        return new SimpleGraph(random, e0, e1, e2, e3, e4, e5, e6, e7, e8);
    }
}
