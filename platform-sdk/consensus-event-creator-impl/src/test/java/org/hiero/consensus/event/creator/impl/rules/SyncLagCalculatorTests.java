// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.rules;

import static com.swirlds.common.test.fixtures.WeightGenerators.BALANCED_1000_PER_NODE;
import static com.swirlds.common.test.fixtures.WeightGenerators.GAUSSIAN;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import java.util.concurrent.atomic.AtomicLong;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class SyncLagCalculatorTests {

    private RandomRosterBuilder rosterBuilder;

    @BeforeEach
    public void setUp() {
        var random = RandomUtils.getRandomPrintSeed();
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
        AtomicLong lag = new AtomicLong(10);
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
        AtomicLong lag = new AtomicLong(10);
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
        AtomicLong counter = new AtomicLong(0);
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
        AtomicLong counter = new AtomicLong(0);
        roster.rosterEntries().subList(0, 3).forEach(entry -> {
            if (entry.nodeId() != NodeId.FIRST_NODE_ID.id()) {
                slc.reportSyncLag(NodeId.of(entry.nodeId()), 10000);
            }
        });

        assertEquals(0, slc.getSyncRoundLag());
    }
}
