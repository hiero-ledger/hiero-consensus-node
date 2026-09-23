// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.gossip.impl.gossip;

import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.hiero.base.utility.test.fixtures.ResettableRandom;
import org.hiero.consensus.event.DefaultIntakeEventCounter;
import org.hiero.consensus.event.IntakeEventCounter;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("DefaultIntakeEventCounter Tests")
class DefaultIntakeEventCounterTests {
    private IntakeEventCounter intakeCounter;

    private NodeId nodeId1;
    private NodeId nodeId2;

    @BeforeEach
    void setup() {
        final ResettableRandom random = getRandomPrintSeed();
        final RosterWrapper roster = RosterWrapperFactory.randomRoster(random, 2);
        nodeId1 = roster.nodeId(0);
        nodeId2 = roster.nodeId(1);

        this.intakeCounter = new DefaultIntakeEventCounter(roster);
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
