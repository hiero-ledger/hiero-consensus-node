// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.rules;

import static org.hiero.consensus.test.fixtures.WeightGenerators.BALANCED_1000_PER_NODE;
import static org.hiero.consensus.test.fixtures.WeightGenerators.GAUSSIAN;
import static org.hiero.consensus.test.fixtures.WeightGenerators.SINGLE_NODE_HAS_ALL;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.base.utility.test.fixtures.ResettableRandom;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class SyncLagCalculatorTests {

    private RandomRosterBuilder rosterBuilder;
    private ResettableRandom random;

    @BeforeEach
    public void setUp() {
        random = RandomUtils.getRandomPrintSeed();
        rosterBuilder = RandomRosterBuilder.create(random);
        rosterBuilder.withSize(10);
    }

    @Test
    public void randomWeightSameLag() {
        final var reportedLag = 123456L;
        rosterBuilder.withWeightGenerator(GAUSSIAN);
        final var roster = rosterBuilder.build();
        final SyncLagCalculator slc = new SyncLagCalculator(NodeId.FIRST_NODE_ID, roster);
        roster.rosterEntries().forEach(entry -> {
            if (entry.nodeId() != NodeId.FIRST_NODE_ID.id()) {
                slc.reportSyncLag(NodeId.of(entry.nodeId()), reportedLag);
            }
        });

        assertEquals(reportedLag, slc.getSyncRoundLag());
    }

    @Test
    public void sameWeightComputeLag() {

        rosterBuilder.withWeightGenerator(BALANCED_1000_PER_NODE);
        final var roster = rosterBuilder.build();
        final SyncLagCalculator slc = new SyncLagCalculator(NodeId.FIRST_NODE_ID, roster);
        final AtomicLong lag = new AtomicLong(10);
        roster.rosterEntries().forEach(entry -> {
            if (entry.nodeId() != NodeId.FIRST_NODE_ID.id()) {
                slc.reportSyncLag(NodeId.of(entry.nodeId()), lag.getAndAdd(10));
            }
        });

        assertEquals(50, slc.getSyncRoundLag());
    }

    @Test
    public void sameWeightComputeLagEvenAmountOfPeers() {

        rosterBuilder.withSize(11);
        rosterBuilder.withWeightGenerator(BALANCED_1000_PER_NODE);
        final var roster = rosterBuilder.build();
        final SyncLagCalculator slc = new SyncLagCalculator(NodeId.FIRST_NODE_ID, roster);
        final AtomicLong lag = new AtomicLong(10);
        roster.rosterEntries().forEach(entry -> {
            if (entry.nodeId() != NodeId.FIRST_NODE_ID.id()) {
                slc.reportSyncLag(NodeId.of(entry.nodeId()), lag.getAndAdd(10));
            }
        });

        assertEquals(55, slc.getSyncRoundLag());
    }

    @Test
    public void randomWeightsALotOfZeroes() {

        rosterBuilder.withSize(10);
        rosterBuilder.withWeightGenerator(GAUSSIAN);
        final var roster = rosterBuilder.build();
        final SyncLagCalculator slc = new SyncLagCalculator(NodeId.FIRST_NODE_ID, roster);
        final AtomicLong counter = new AtomicLong(0);
        roster.rosterEntries().forEach(entry -> {
            if (entry.nodeId() != NodeId.FIRST_NODE_ID.id()) {
                if (counter.incrementAndGet() < 7) {
                    slc.reportSyncLag(NodeId.of(entry.nodeId()), 0);
                } else {
                    slc.reportSyncLag(NodeId.of(entry.nodeId()), 10000);
                }
            }
        });

        assertEquals(0, slc.getSyncRoundLag());
    }

    @Test
    public void missingNodesAssumedToBeZero() {

        rosterBuilder.withSize(10);
        rosterBuilder.withWeightGenerator(GAUSSIAN);
        final var roster = rosterBuilder.build();
        final SyncLagCalculator slc = new SyncLagCalculator(NodeId.FIRST_NODE_ID, roster);
        roster.rosterEntries().subList(0, 3).forEach(entry -> {
            if (entry.nodeId() != NodeId.FIRST_NODE_ID.id()) {
                slc.reportSyncLag(NodeId.of(entry.nodeId()), 10000);
            }
        });

        assertEquals(0, slc.getSyncRoundLag());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
    public void singleNodeNetworkRealNode(final int zeroWeightNodeCount) {

        rosterBuilder.withSize(zeroWeightNodeCount + 1);
        rosterBuilder.withWeightGenerator(SINGLE_NODE_HAS_ALL);
        final var roster = rosterBuilder.build();
        final SyncLagCalculator slc = new SyncLagCalculator(NodeId.FIRST_NODE_ID, roster);
        assertEquals(0, slc.getSyncRoundLag());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9})
    public void singleNodeNetworkUselessNode(final int zeroWeightNodeCount) {

        rosterBuilder.withSize(zeroWeightNodeCount + 1);
        rosterBuilder.withWeightGenerator(SINGLE_NODE_HAS_ALL);
        final var roster = rosterBuilder.build();
        final var selfNodeId = NodeId.of(roster.rosterEntries()
                .get(1 + random.nextInt(zeroWeightNodeCount))
                .nodeId());
        final SyncLagCalculator slc = new SyncLagCalculator(selfNodeId, roster);

        for (int i = 0; i <= zeroWeightNodeCount; i++) {
            final var nodeId = NodeId.of(roster.rosterEntries().get(i).nodeId());
            if (!nodeId.equals(selfNodeId)) {
                slc.reportSyncLag(nodeId, random.nextLong(10000000));
            }
        }
        final long reportedLag = 123456L;
        slc.reportSyncLag(NodeId.FIRST_NODE_ID, reportedLag);

        assertEquals(reportedLag, slc.getSyncRoundLag());
    }
}
