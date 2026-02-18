// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.eventhandling;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertAllDatabasesClosed;
import static com.swirlds.platform.test.fixtures.state.RandomSignedStateGenerator.releaseAllBuiltSignedStates;
import static org.hiero.consensus.model.PbjConverters.toPbjTimestamp;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import com.swirlds.platform.system.status.actions.FreezePeriodEnteredAction;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.time.Instant;
import java.util.List;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.roster.test.fixtures.RandomRosterBuilder;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link DefaultTransactionHandler}.
 */
class DefaultTransactionHandlerTests {
    private Randotron random;
    private Roster roster;

    @BeforeEach
    void setUp() {
        random = Randotron.create();
        roster = RandomRosterBuilder.create(random)
                .withRealKeysEnabled(false)
                .withSize(4)
                .build();
    }

    /**
     * Constructs a new consensus round with a few events for testing.
     *
     * @param pcesRound whether the round is a PCES round
     * @return the new round
     */
    private ConsensusRound newConsensusRound(final boolean pcesRound) {
        final List<PlatformEvent> events = List.of(
                new TestingEventBuilder(random)
                        .setAppTransactionCount(3)
                        .setSystemTransactionCount(1)
                        .setConsensusTimestamp(random.nextInstant())
                        .build(),
                new TestingEventBuilder(random)
                        .setAppTransactionCount(2)
                        .setSystemTransactionCount(0)
                        .setConsensusTimestamp(random.nextInstant())
                        .build(),
                // test should have at least one event with no transactions to ensure that these events are provided to
                // the app
                new TestingEventBuilder(random)
                        .setAppTransactionCount(0)
                        .setSystemTransactionCount(0)
                        .setConsensusTimestamp(random.nextInstant())
                        .build());
        events.forEach(PlatformEvent::signalPrehandleCompletion);
        final ConsensusRound round = new ConsensusRound(
                roster,
                events,
                EventWindow.getGenesisEventWindow(),
                getSnapshotWithTimestamp(Instant.now().minusMillis(1)),
                pcesRound,
                random.nextInstant());

        round.getStreamedEvents().forEach(cesEvent -> cesEvent.getRunningHash().setHash(random.nextHash()));
        return round;
    }

    @DisplayName("Normal operation")
    @ParameterizedTest
    @CsvSource({"false", "true"})
    void normalOperation(final boolean pcesRound) throws InterruptedException {
        try (final TransactionHandlerTester tester = new TransactionHandlerTester()) {
            final ConsensusRound consensusRound = newConsensusRound(pcesRound);

            final TransactionHandlerResult handlerOutput =
                    tester.getTransactionHandler().handleConsensusRound(consensusRound);
            assertNotEquals(null, handlerOutput, "new state should have been created");
            assertEquals(
                    1,
                    handlerOutput
                            .stateWithHashComplexity()
                            .reservedSignedState()
                            .get()
                            .getReservationCount(),
                    "state should be returned with a reservation");

            assertEquals(0, tester.getSubmittedActions().size(), "the freeze status should not have been submitted");

            assertEquals(1, tester.getHandledRounds().size(), "a round should have been handled");
            assertSame(
                    consensusRound,
                    tester.getHandledRounds().getFirst(),
                    "the round handled should be the one we provided");
            boolean eventWithNoTransactions = false;
            for (final ConsensusEvent consensusEvent : tester.getHandledRounds().getFirst()) {
                if (!consensusEvent.consensusTransactionIterator().hasNext()) {
                    eventWithNoTransactions = true;
                    break;
                }
            }
            assertTrue(
                    eventWithNoTransactions,
                    "at least one event with no transactions should have been provided to the app");
            assertNull(tester.getPlatformState().getLastFrozenTime(), "no freeze time should have been set");

            // Assert that the legacy running hash was updated with the expected value
            assertEquals(
                    tester.getLegacyRunningHash(),
                    consensusRound
                            .getStreamedEvents()
                            .getLast()
                            .getRunningHash()
                            .getFutureHash()
                            .getAndRethrow(),
                    "the running hash should be updated");
            assertEquals(
                    pcesRound,
                    handlerOutput
                            .stateWithHashComplexity()
                            .reservedSignedState()
                            .get()
                            .isPcesRound(),
                    "the state should match the PCES boolean");
            verify(tester.getStateEventHandler())
                    .onSealConsensusRound(
                            consensusRound, tester.getStateLifecycleManager().getLatestImmutableState());
        }
    }

    @Test
    @DisplayName("Round in freeze period")
    void freezeHandling() throws InterruptedException {
        try (final TransactionHandlerTester tester = new TransactionHandlerTester()) {
            tester.enableFreezePeriod();
            final ConsensusRound consensusRound = newConsensusRound(false);
            final TransactionHandlerResult handlerOutput =
                    tester.getTransactionHandler().handleConsensusRound(consensusRound);
            assertNotNull(handlerOutput, "new state should have been created");
            assertEquals(
                    1,
                    handlerOutput
                            .stateWithHashComplexity()
                            .reservedSignedState()
                            .get()
                            .getReservationCount(),
                    "state should be returned with a reservation");
            assertEquals(1, tester.getSubmittedActions().size(), "the freeze status should have been submitted");
            // The freeze action is the first action submitted.
            assertEquals(
                    FreezePeriodEnteredAction.class,
                    tester.getSubmittedActions().getFirst().getClass());
            assertEquals(1, tester.getHandledRounds().size(), "a round should have been handled");
            assertSame(consensusRound, tester.getHandledRounds().getFirst(), "it should be the round we provided");

            final ConsensusRound postFreezeConsensusRound = newConsensusRound(false);
            final TransactionHandlerResult postFreezeOutput =
                    tester.getTransactionHandler().handleConsensusRound(postFreezeConsensusRound);
            assertNull(postFreezeOutput, "no state should be created after freeze period");

            assertEquals(1, tester.getSubmittedActions().size(), "no new status should have been submitted");
            assertEquals(1, tester.getHandledRounds().size(), "no new rounds should have been handled");
            assertSame(consensusRound, tester.getHandledRounds().getFirst(), "it should same round as before");
            assertEquals(
                    tester.getLegacyRunningHash(),
                    consensusRound
                            .getStreamedEvents()
                            .getLast()
                            .getRunningHash()
                            .getFutureHash()
                            .getAndRethrow(),
                    "the running hash should from the freeze round");
        }
    }

    private static @NonNull ConsensusSnapshot getSnapshotWithTimestamp(final @NonNull Instant consensusTimestamp) {
        return ConsensusSnapshot.newBuilder()
                .round(ConsensusConstants.ROUND_FIRST)
                .judgeIds(List.of())
                .minimumJudgeInfoList(
                        List.of(new MinimumJudgeInfo(ConsensusConstants.ROUND_FIRST, ConsensusConstants.ROUND_FIRST)))
                .nextConsensusNumber(ConsensusConstants.FIRST_CONSENSUS_NUMBER)
                .consensusTimestamp(toPbjTimestamp(consensusTimestamp))
                .build();
    }

    @AfterAll
    static void tearDown() {
        releaseAllBuiltSignedStates();
        assertAllDatabasesClosed();
    }
}
