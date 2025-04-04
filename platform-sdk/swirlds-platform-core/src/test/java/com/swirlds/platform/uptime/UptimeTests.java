// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.uptime;

import static com.swirlds.platform.test.fixtures.addressbook.RosterTestUtils.addRandomRosterEntryToRoster;
import static com.swirlds.platform.test.fixtures.addressbook.RosterTestUtils.dropRosterEntryFromRoster;
import static com.swirlds.platform.uptime.UptimeData.NO_ROUND;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.common.context.PlatformContext;
import com.swirlds.common.test.fixtures.platform.TestPlatformContextBuilder;
import com.swirlds.platform.system.status.StatusActionSubmitter;
import com.swirlds.platform.test.fixtures.addressbook.RandomRosterBuilder;
import com.swirlds.platform.test.fixtures.event.TestingEventBuilder;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.utility.CommonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Uptime Tests")
class UptimeTests {

    private static List<PlatformEvent> generateEvents(
            @NonNull final Random random,
            @NonNull final FakeTime time,
            @NonNull final Duration roundDuration,
            @NonNull final Roster roster,
            final int count,
            @NonNull Set<NodeId> noEvents,
            @NonNull Set<NodeId> noJudges) {

        final List<PlatformEvent> events = new ArrayList<>(count);
        final Set<NodeId> firstEventCreated = new HashSet<>();
        final int size = roster.rosterEntries().size();
        while (events.size() < count) {

            final NodeId nodeId =
                    NodeId.of(roster.rosterEntries().get(random.nextInt(size)).nodeId());
            if (noEvents.contains(nodeId)) {
                continue;
            }
            final PlatformEvent platformEvent = new TestingEventBuilder(random)
                    .setCreatorId(nodeId)
                    .setConsensusTimestamp(time.now())
                    .build();

            if (!noJudges.contains(nodeId) && firstEventCreated.add(nodeId)) {
                // event.setFamous(true);
                // event.setJudgeTrue();
                firstEventCreated.add(nodeId);
            }
            time.tick(roundDuration.dividedBy(count));

            events.add(platformEvent);
        }

        return events;
    }

    private static ConsensusRound mockRound(
            @NonNull final List<PlatformEvent> events, @NonNull final Roster roster, final long roundNum) {
        final ConsensusSnapshot snapshot = mock(ConsensusSnapshot.class);
        final ConsensusRound round =
                new ConsensusRound(roster, events, mock(EventWindow.class), snapshot, false, Instant.now());
        final Instant consensusTimestamp = events.get(events.size() - 1).getConsensusTimestamp();
        when(snapshot.consensusTimestamp()).thenReturn(CommonUtils.toPbjTimestamp(consensusTimestamp));
        when(snapshot.round()).thenReturn(roundNum);
        when(round.getRoundNum()).thenReturn(roundNum);
        return round;
    }

    @Test
    @DisplayName("Round Scan Test")
    void roundScanTest() {
        final Random random = getRandomPrintSeed();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final FakeTime time = new FakeTime();

        final Roster roster = RandomRosterBuilder.create(random).withSize(10).build();
        final NodeId selfId = NodeId.of(roster.rosterEntries().getFirst().nodeId());

        final UptimeTracker uptimeTracker =
                new UptimeTracker(platformContext, roster, mock(StatusActionSubmitter.class), selfId, time);
        final UptimeData uptimeData = uptimeTracker.uptimeData;

        // First, simulate a round starting at genesis
        final int eventCount = 100;
        final Set<NodeId> noFirstRoundEvents = Set.of(
                NodeId.of(roster.rosterEntries().getFirst().nodeId()),
                NodeId.of(roster.rosterEntries().get(1).nodeId()));
        final Set<NodeId> noFirstRoundJudges = Set.of(
                NodeId.of(roster.rosterEntries().get(8).nodeId()),
                NodeId.of(roster.rosterEntries().get(9).nodeId()));
        final List<PlatformEvent> firstRoundEvents = generateEvents(
                random, time, Duration.ofSeconds(1), roster, eventCount, noFirstRoundEvents, noFirstRoundJudges);

        final ConsensusRound roundOne = mockRound(firstRoundEvents, roster, 1);
        uptimeTracker.handleRound(roundOne);

        roster.rosterEntries().forEach(entry -> {
            final NodeId nodeId = NodeId.of(entry.nodeId());
            ConsensusEvent judge = null;
            ConsensusEvent lastEvent = null;

            for (final ConsensusEvent event : firstRoundEvents) {
                if (!Objects.equals(event.getCreatorId(), nodeId)) {
                    continue;
                }
                if (judge == null && !noFirstRoundJudges.contains(nodeId)) {
                    judge = event;
                }
                lastEvent = event;
            }

            // Temporarily disabled until we properly detect judges in a round
            //            if (judge != null) {
            //                assertEquals(1, genesisUptimeData.getLastJudgeRound(nodeId));
            //                assertEquals(judge.getConsensusTimestamp(),
            // genesisUptimeData.getLastJudgeTime(nodeId));
            //            } else {

            assertEquals(NO_ROUND, uptimeData.getLastJudgeRound(nodeId));
            assertNull(uptimeData.getLastJudgeTime(nodeId));
            //            }

            if (lastEvent != null) {
                assertEquals(1, uptimeData.getLastEventRound(nodeId));
                assertEquals(lastEvent.getConsensusTimestamp(), uptimeData.getLastEventTime(nodeId));
            } else {
                assertEquals(NO_ROUND, uptimeData.getLastEventRound(nodeId));
                assertNull(uptimeData.getLastEventTime(nodeId));
            }
        });

        final Set<NodeId> noSecondRoundEvents = Set.of(
                NodeId.of(roster.rosterEntries().get(0).nodeId()),
                NodeId.of(roster.rosterEntries().get(2).nodeId()));
        final Set<NodeId> noSecondRoundJudges = Set.of(
                NodeId.of(roster.rosterEntries().get(7).nodeId()),
                NodeId.of(roster.rosterEntries().get(9).nodeId()));
        final List<PlatformEvent> secondRoundEvents = generateEvents(
                random, time, Duration.ofSeconds(1), roster, eventCount, noSecondRoundEvents, noSecondRoundJudges);

        final ConsensusRound roundTwo = mockRound(secondRoundEvents, roster, 2);
        uptimeTracker.handleRound(roundTwo);

        roster.rosterEntries().forEach(entry -> {
            final NodeId nodeId = NodeId.of(entry.nodeId());
            ConsensusEvent judge = null;
            ConsensusEvent lastEvent = null;

            for (final ConsensusEvent event : secondRoundEvents) {
                if (!Objects.equals(event.getCreatorId(), nodeId)) {
                    continue;
                }
                if (judge == null && !noSecondRoundJudges.contains(nodeId)) {
                    judge = event;
                }
                lastEvent = event;
            }

            // Temporarily disabled until we properly detect judges in a round
            assertEquals(NO_ROUND, uptimeData.getLastJudgeRound(nodeId));
            assertNull(uptimeData.getLastJudgeTime(nodeId));
            //            if (judge != null) {
            //                assertEquals(2, nextRoundUptimeData.getLastJudgeRound(nodeId));
            //                assertEquals(judge.getConsensusTimestamp(),
            // nextRoundUptimeData.getLastJudgeTime(nodeId));
            //            } else {
            //                assertEquals(
            //                        genesisUptimeData.getLastJudgeRound(nodeId),
            //                        nextRoundUptimeData.getLastJudgeRound(nodeId));
            //                assertEquals(
            //                        genesisUptimeData.getLastJudgeTime(nodeId),
            //                        nextRoundUptimeData.getLastJudgeTime(nodeId));
            //            }

            if (lastEvent != null) {
                assertEquals(2, uptimeData.getLastEventRound(nodeId));
                assertEquals(lastEvent.getConsensusTimestamp(), uptimeData.getLastEventTime(nodeId));
            }
        });
    }

    @Test
    @DisplayName("Round Scan Changing Address Book Test")
    void roundScanChangingRosterTest() {
        final Random random = getRandomPrintSeed();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final FakeTime time = new FakeTime();

        final Roster roster = RandomRosterBuilder.create(random).withSize(10).build();
        final NodeId selfId = NodeId.of(roster.rosterEntries().getFirst().nodeId());

        final UptimeTracker uptimeTracker =
                new UptimeTracker(platformContext, roster, mock(StatusActionSubmitter.class), selfId, time);
        final UptimeData uptimeData = uptimeTracker.uptimeData;
        // First, simulate a round starting at genesis
        final int eventCount = 100;
        final Set<NodeId> noFirstRoundEvents = Set.of(
                NodeId.of(roster.rosterEntries().get(0).nodeId()),
                NodeId.of(roster.rosterEntries().get(1).nodeId()));
        final Set<NodeId> noFirstRoundJudges = Set.of(
                NodeId.of(roster.rosterEntries().get(8).nodeId()),
                NodeId.of(roster.rosterEntries().get(9).nodeId()));
        final List<PlatformEvent> firstRoundEvents = generateEvents(
                random, time, Duration.ofSeconds(1), roster, eventCount, noFirstRoundEvents, noFirstRoundJudges);

        roster.rosterEntries().forEach(entry -> {
            final NodeId nodeId = NodeId.of(entry.nodeId());
            assertNull(uptimeData.getLastEventTime(nodeId));
            assertNull(uptimeData.getLastJudgeTime(nodeId));
            assertEquals(NO_ROUND, uptimeData.getLastEventRound(nodeId));
            assertEquals(NO_ROUND, uptimeData.getLastJudgeRound(nodeId));
        });

        final ConsensusRound roundOne = mockRound(firstRoundEvents, roster, 1);
        uptimeTracker.handleRound(roundOne);

        roster.rosterEntries().forEach(entry -> {
            final NodeId nodeId = NodeId.of(entry.nodeId());
            ConsensusEvent judge = null;
            ConsensusEvent lastEvent = null;

            for (final ConsensusEvent event : firstRoundEvents) {
                if (!Objects.equals(event.getCreatorId(), nodeId)) {
                    continue;
                }
                if (judge == null && !noFirstRoundJudges.contains(nodeId)) {
                    judge = event;
                }
                lastEvent = event;
            }

            // Temporarily disabled until we properly detect judges in a round
            //            if (judge != null) {
            //                assertEquals(1, genesisUptimeData.getLastJudgeRound(nodeId));
            //                assertEquals(judge.getConsensusTimestamp(),
            // genesisUptimeData.getLastJudgeTime(nodeId));
            //            } else {
            assertEquals(NO_ROUND, uptimeData.getLastJudgeRound(nodeId));
            assertNull(uptimeData.getLastJudgeTime(nodeId));
            //            }

            if (lastEvent != null) {
                assertEquals(1, uptimeData.getLastEventRound(nodeId));
                assertEquals(lastEvent.getConsensusTimestamp(), uptimeData.getLastEventTime(nodeId));
            } else {
                assertEquals(NO_ROUND, uptimeData.getLastEventRound(nodeId));
                assertNull(uptimeData.getLastEventTime(nodeId));
            }
        });

        // Simulate a following round with a different address book
        final long nodeToRemove = roster.rosterEntries().getFirst().nodeId();
        final Roster intermediateRoster = dropRosterEntryFromRoster(roster, nodeToRemove);
        final Roster newRoster = addRandomRosterEntryToRoster(intermediateRoster, 12345L, random);
        final Set<NodeId> noSecondRoundEvents = Set.of();
        final Set<NodeId> noSecondRoundJudges = Set.of();
        final List<PlatformEvent> secondRoundEvents = generateEvents(
                random, time, Duration.ofSeconds(1), newRoster, eventCount, noSecondRoundEvents, noSecondRoundJudges);

        final ConsensusRound roundTwo = mockRound(secondRoundEvents, newRoster, 2);

        uptimeTracker.handleRound(roundTwo);

        newRoster.rosterEntries().forEach(entry -> {
            final NodeId nodeId = NodeId.of(entry.nodeId());
            ConsensusEvent judge = null;
            ConsensusEvent lastEvent = null;

            for (final ConsensusEvent event : secondRoundEvents) {
                if (!Objects.equals(event.getCreatorId(), nodeId)) {
                    continue;
                }
                if (judge == null) {
                    judge = event;
                }
                lastEvent = event;
            }

            // Temporarily disabled until we properly detect judges in a round
            assertEquals(NO_ROUND, uptimeData.getLastJudgeRound(nodeId));
            assertNull(uptimeData.getLastJudgeTime(nodeId));
            //            if (judge != null) {
            //                assertEquals(2, nextRoundUptimeData.getLastJudgeRound(nodeId));
            //                assertEquals(judge.getConsensusTimestamp(),
            // nextRoundUptimeData.getLastJudgeTime(nodeId));
            //            }

            if (lastEvent != null) {
                assertEquals(2, uptimeData.getLastEventRound(nodeId));
                assertEquals(lastEvent.getConsensusTimestamp(), uptimeData.getLastEventTime(nodeId));
            }
        });

        final NodeId nodeIdToRemove = NodeId.of(nodeToRemove);
        assertNull(uptimeData.getLastJudgeTime(nodeIdToRemove));
        assertNull(uptimeData.getLastEventTime(nodeIdToRemove));
        assertEquals(NO_ROUND, uptimeData.getLastJudgeRound(nodeIdToRemove));
        assertEquals(NO_ROUND, uptimeData.getLastEventRound(nodeIdToRemove));
    }

    @Test
    @DisplayName("Degraded Test")
    void degradedTest() {
        final Random random = getRandomPrintSeed();

        final PlatformContext platformContext =
                TestPlatformContextBuilder.create().build();
        final FakeTime time = new FakeTime();

        final Roster roster = RandomRosterBuilder.create(random).withSize(3).build();
        final NodeId selfId = NodeId.of(roster.rosterEntries().getFirst().nodeId());

        final UptimeTracker uptimeTracker =
                new UptimeTracker(platformContext, roster, mock(StatusActionSubmitter.class), selfId, time);
        final UptimeData uptimeData = uptimeTracker.uptimeData;

        // First, simulate a round starting at genesis
        final int eventCount = 100;
        final List<PlatformEvent> firstRoundEvents =
                generateEvents(random, time, Duration.ofSeconds(1), roster, eventCount, Set.of(), Set.of());

        roster.rosterEntries().forEach(entry -> {
            final NodeId nodeId = NodeId.of(entry.nodeId());
            assertNull(uptimeData.getLastEventTime(nodeId));
            assertNull(uptimeData.getLastJudgeTime(nodeId));
            assertEquals(NO_ROUND, uptimeData.getLastEventRound(nodeId));
            assertEquals(NO_ROUND, uptimeData.getLastJudgeRound(nodeId));
        });

        final ConsensusRound roundOne = mockRound(firstRoundEvents, roster, 1);
        uptimeTracker.handleRound(roundOne);

        // Simulate a following round, but allow a long time to pass
        time.tick(Duration.ofSeconds(30));

        final Set<NodeId> noSecondRoundEvents =
                Set.of(NodeId.of(roster.rosterEntries().getFirst().nodeId()));
        final List<PlatformEvent> secondRoundEvents =
                generateEvents(random, time, Duration.ofSeconds(1), roster, eventCount, noSecondRoundEvents, Set.of());

        final ConsensusRound roundTwo = mockRound(secondRoundEvents, roster, 2);
        uptimeTracker.handleRound(roundTwo);

        assertTrue(uptimeTracker.isSelfDegraded());

        // Once one of the node's events reaches consensus again, it should no longer be degraded

        final List<PlatformEvent> thirdRoundEvents =
                generateEvents(random, time, Duration.ofSeconds(1), roster, eventCount, Set.of(), Set.of());

        final ConsensusRound roundThree = mockRound(thirdRoundEvents, roster, 3);
        uptimeTracker.handleRound(roundThree);

        assertFalse(uptimeTracker.isSelfDegraded());
    }
}
