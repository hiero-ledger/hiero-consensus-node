// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.gossip;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterEntryBuilder;
import java.util.ArrayList;
import org.hiero.base.utility.test.fixtures.ResettableRandom;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultIntakeEventCounter Tests")
class DefaultIntakeEventCounterTests {
    private IntakeEventCounter intakeCounter;

    final NodeId nodeId1 = NodeId.of(1);
    final NodeId nodeId2 = NodeId.of(2);

    @BeforeEach
    void setup() {
        final ResettableRandom random = getRandomPrintSeed();
        final ArrayList<RosterEntry> rosterEntries = new ArrayList<>();
        rosterEntries.add(
                RandomRosterEntryBuilder.create(random).withNodeId(nodeId1.id()).build());
        rosterEntries.add(
                RandomRosterEntryBuilder.create(random).withNodeId(nodeId2.id()).build());

        this.intakeCounter = new DefaultIntakeEventCounter(
                Roster.newBuilder().rosterEntries(rosterEntries).build());
    }

    @Test
    @DisplayName("Test unprocessed events check")
    void unprocessedEvents() {
        assertFalse(intakeCounter.hasUnprocessedEvents(nodeId1));
        assertFalse(intakeCounter.hasUnprocessedEvents(nodeId1));

        intakeCounter.eventEnteredIntakePipeline(nodeId1);
        assertTrue(intakeCounter.hasUnprocessedEvents(nodeId1));
        assertFalse(intakeCounter.hasUnprocessedEvents(nodeId2));

        intakeCounter.eventEnteredIntakePipeline(nodeId1);
        assertTrue(intakeCounter.hasUnprocessedEvents(nodeId1));
        assertFalse(intakeCounter.hasUnprocessedEvents(nodeId2));

        intakeCounter.eventExitedIntakePipeline(nodeId1);
        assertTrue(intakeCounter.hasUnprocessedEvents(nodeId1));
        assertFalse(intakeCounter.hasUnprocessedEvents(nodeId2));

        intakeCounter.eventExitedIntakePipeline(nodeId1);
        assertFalse(intakeCounter.hasUnprocessedEvents(nodeId1));
        assertFalse(intakeCounter.hasUnprocessedEvents(nodeId2));
    }

    @Test
    @DisplayName("Test reset")
    void reset() {
        intakeCounter.eventEnteredIntakePipeline(nodeId1);
        intakeCounter.eventEnteredIntakePipeline(nodeId1);
        intakeCounter.eventEnteredIntakePipeline(nodeId2);

        assertTrue(intakeCounter.hasUnprocessedEvents(nodeId1));
        assertTrue(intakeCounter.hasUnprocessedEvents(nodeId2));

        intakeCounter.reset();
        assertFalse(intakeCounter.hasUnprocessedEvents(nodeId1));
        assertFalse(intakeCounter.hasUnprocessedEvents(nodeId2));
    }
}
