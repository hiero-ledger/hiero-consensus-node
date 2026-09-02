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
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.roster.test.fixtures.RosterFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class SyncLagCalculatorTests {

    private ResettableRandom random;

    @BeforeEach
    public void setUp() {
        random = RandomUtils.getRandomPrintSeed();
    }

    @Test
    public void randomWeightSameLag() {
        final long reportedLag = 123456L;
        final RosterWrapper roster = RosterWrapper.of(RosterFactory.randomRoster(random, 10, GAUSSIAN));
        final SyncLagCalculator slc = new SyncLagCalculator(NodeId.FIRST_NODE_ID, roster);
        roster.rosterEntries().forEach(entry -> {
            if (!NodeId.FIRST_NODE_ID.equals(entry.nodeId())) {
                slc.reportSyncLag(entry.nodeId(), reportedLag);
            }
        });

        assertEquals(reportedLag, slc.getSyncRoundLag());
    }

    @Test
    public void sameWeightComputeLag() {

        final RosterWrapper roster = RosterWrapper.of(RosterFactory.randomRoster(random, 10, BALANCED_1000_PER_NODE));
        final SyncLagCalculator slc = new SyncLagCalculator(NodeId.FIRST_NODE_ID, roster);
        final AtomicLong lag = new AtomicLong(10);
        roster.rosterEntries().forEach(entry -> {
            if (!NodeId.FIRST_NODE_ID.equals(entry.nodeId())) {
                slc.reportSyncLag(entry.nodeId(), lag.getAndAdd(10));
            }
        });

        assertEquals(50, slc.getSyncRoundLag());
    }

    @Test
    public void sameWeightComputeLagEvenAmountOfPeers() {

        final RosterWrapper roster = RosterWrapper.of(RosterFactory.randomRoster(random, 11, BALANCED_1000_PER_NODE));
        final SyncLagCalculator slc = new SyncLagCalculator(NodeId.FIRST_NODE_ID, roster);
        final AtomicLong lag = new AtomicLong(10);
        roster.rosterEntries().forEach(entry -> {
            if (!NodeId.FIRST_NODE_ID.equals(entry.nodeId())) {
                slc.reportSyncLag(entry.nodeId(), lag.getAndAdd(10));
            }
        });

        assertEquals(55, slc.getSyncRoundLag());
    }

    @Test
    public void randomWeightsALotOfZeroes() {

        final RosterWrapper roster = RosterWrapper.of(RosterFactory.randomRoster(random, 10, GAUSSIAN));
        final SyncLagCalculator slc = new SyncLagCalculator(NodeId.FIRST_NODE_ID, roster);
        final AtomicLong counter = new AtomicLong(0);
        roster.rosterEntries().forEach(entry -> {
            if (!NodeId.FIRST_NODE_ID.equals(entry.nodeId())) {
                if (counter.incrementAndGet() < 7) {
                    slc.reportSyncLag(entry.nodeId(), 0);
                } else {
                    slc.reportSyncLag(entry.nodeId(), 10000);
                }
            }
        });

        assertEquals(0, slc.getSyncRoundLag());
    }

    @Test
    public void missingNodesAssumedToBeZero() {

        final RosterWrapper roster = RosterWrapper.of(RosterFactory.randomRoster(random, 10, GAUSSIAN));
        final SyncLagCalculator slc = new SyncLagCalculator(NodeId.FIRST_NODE_ID, roster);
        roster.rosterEntries().subList(0, 3).forEach(entry -> {
            if (!NodeId.FIRST_NODE_ID.equals(entry.nodeId())) {
                slc.reportSyncLag(entry.nodeId(), 10000);
            }
        });

        assertEquals(0, slc.getSyncRoundLag());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9})
    public void singleNodeNetworkRealNode(final int zeroWeightNodeCount) {

        final RosterWrapper roster =
                RosterWrapper.of(RosterFactory.randomRoster(random, zeroWeightNodeCount + 1, SINGLE_NODE_HAS_ALL));
        final SyncLagCalculator slc = new SyncLagCalculator(NodeId.FIRST_NODE_ID, roster);
        assertEquals(0, slc.getSyncRoundLag());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9})
    public void singleNodeNetworkUselessNode(final int zeroWeightNodeCount) {

        final RosterWrapper roster =
                RosterWrapper.of(RosterFactory.randomRoster(random, zeroWeightNodeCount + 1, SINGLE_NODE_HAS_ALL));
        final NodeId selfNodeId = roster.rosterEntries()
                .get(1 + random.nextInt(zeroWeightNodeCount))
                .nodeId();
        final SyncLagCalculator slc = new SyncLagCalculator(selfNodeId, roster);

        for (int i = 0; i <= zeroWeightNodeCount; i++) {
            final NodeId nodeId = roster.rosterEntries().get(i).nodeId();
            if (!nodeId.equals(selfNodeId)) {
                slc.reportSyncLag(nodeId, random.nextLong(10000000));
            }
        }
        final long reportedLag = 123456L;
        slc.reportSyncLag(NodeId.FIRST_NODE_ID, reportedLag);

        assertEquals(reportedLag, slc.getSyncRoundLag());
    }
}
