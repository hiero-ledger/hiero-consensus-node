// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.transaction.handling.internal;

import static com.swirlds.merkledb.test.fixtures.MerkleDbTestUtils.assertAllDatabasesClosed;
import static org.hiero.consensus.model.PbjConverters.toPbjTimestamp;
import static org.hiero.consensus.state.test.fixtures.RandomSignedStateGenerator.releaseAllBuiltSignedStates;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.hapi.platform.state.MinimumJudgeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.time.Instant;
import java.util.List;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusConstants;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.roster.test.fixtures.RosterFactory;
import org.hiero.consensus.state.signed.DefaultStateGarbageCollector;
import org.hiero.consensus.status.actions.FreezePeriodEnteredAction;
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
        roster = RosterFactory.randomRoster(random, 4);
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
            try {
                assertNotEquals(null, handlerOutput, "new state should have been created");
                assertEquals(
                        2,
                        handlerOutput
                                .stateWithHashComplexity()
                                .reservedSignedState()
                                .get()
                                .getReservationCount(),
                        "state should be returned with separate hashing and prehandle reservations");
                assertSame(
                        handlerOutput
                                .stateWithHashComplexity()
                                .reservedSignedState()
                                .get(),
                        handlerOutput.stateForPrehandle().get(),
                        "the same signed state should be used for hashing and prehandle outside a freeze round");

                assertEquals(
                        0, tester.getSubmittedActions().size(), "the freeze status should not have been submitted");

                assertEquals(1, tester.getHandledRounds().size(), "a round should have been handled");
                assertSame(
                        consensusRound,
                        tester.getHandledRounds().getFirst(),
                        "the round handled should be the one we provided");
                boolean eventWithNoTransactions = false;
                for (final ConsensusEvent consensusEvent :
                        tester.getHandledRounds().getFirst()) {
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
                                consensusRound,
                                tester.getStateLifecycleManager().getLatestImmutableState());
            } finally {
                releaseHandlerOutput(handlerOutput);
            }
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
            try {
                assertNotNull(handlerOutput, "new state should have been created");
                assertEquals(
                        1,
                        handlerOutput
                                .stateWithHashComplexity()
                                .reservedSignedState()
                                .get()
                                .getReservationCount(),
                        "the real freeze state should only have the hashing pipeline reservation");
                assertEquals(
                        1,
                        handlerOutput.stateForPrehandle().get().getReservationCount(),
                        "the prehandle carrier should have its own reservation");

                final var freezeState = handlerOutput
                        .stateWithHashComplexity()
                        .reservedSignedState()
                        .get();
                final var prehandleState = handlerOutput.stateForPrehandle().get();
                assertNotSame(
                        freezeState, prehandleState, "prehandle should use a distinct signed state during freeze");
                assertNotSame(
                        freezeState.getState(),
                        prehandleState.getState(),
                        "prehandle should use a fast copy instead of the real freeze state");
                assertTrue(freezeState.isFreezeState(), "the state sent for hashing should be the real freeze state");
                assertFalse(
                        prehandleState.isFreezeState(),
                        "the prehandle-only carrier should not be identified as the real freeze state");
                assertFalse(
                        prehandleState.isStateToSave(), "the prehandle-only carrier should not be marked for saving");
                assertEquals(
                        freezeState.getRound(),
                        prehandleState.getRound(),
                        "the copied state should have the same round");
                assertEquals(
                        freezeState.getConsensusTimestamp(),
                        prehandleState.getConsensusTimestamp(),
                        "the copied state should have the same consensus timestamp");
                assertTrue(freezeState.getState().isImmutable(), "the real freeze state should be immutable");
                assertTrue(prehandleState.getState().isImmutable(), "the prehandle state should be immutable");
                assertSame(
                        prehandleState.getState(),
                        tester.getStateLifecycleManager().getLatestImmutableState(),
                        "the lifecycle manager should retain the prehandle copy");
                assertNotSame(
                        prehandleState.getState(),
                        tester.getStateLifecycleManager().getMutableState(),
                        "the lifecycle manager should retain a separate mutable successor");
                assertFalse(
                        tester.getStateLifecycleManager().getMutableState().isImmutable(),
                        "the mutable successor should remain mutable");
                verify(tester.getStateEventHandler()).onFreezeStateCopied(prehandleState.getState());

                assertEquals(1, tester.getSubmittedActions().size(), "the freeze status should have been submitted");
                assertEquals(
                        FreezePeriodEnteredAction.class,
                        tester.getSubmittedActions().getFirst().getClass(),
                        "the freeze action should be the first submitted action");
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
            } finally {
                releaseHandlerOutput(handlerOutput);
            }
        }
    }

    @Test
    @DisplayName("Synchronous freeze uses the real freeze state for prehandle")
    void synchronousFreezeDoesNotCreatePrehandleCopy() throws InterruptedException {
        try (final TransactionHandlerTester tester = new TransactionHandlerTester(false)) {
            tester.enableFreezePeriod();
            final TransactionHandlerResult handlerOutput =
                    tester.getTransactionHandler().handleConsensusRound(newConsensusRound(false));
            try {
                assertNotNull(handlerOutput, "new state should have been created");
                final var freezeState = handlerOutput
                        .stateWithHashComplexity()
                        .reservedSignedState()
                        .get();
                assertSame(
                        freezeState,
                        handlerOutput.stateForPrehandle().get(),
                        "synchronous saving should keep using the real freeze state for prehandle");
                assertSame(
                        freezeState.getState(),
                        tester.getStateLifecycleManager().getLatestImmutableState(),
                        "synchronous saving should not make a second immutable copy");
                assertEquals(
                        2,
                        freezeState.getReservationCount(),
                        "the real freeze state should have hashing and prehandle reservations");
                verify(tester.getStateEventHandler(), never()).onFreezeStateCopied(any());
            } finally {
                releaseHandlerOutput(handlerOutput);
            }
        }
    }

    private static void releaseHandlerOutput(@Nullable final TransactionHandlerResult handlerOutput) {
        if (handlerOutput == null || handlerOutput.stateWithHashComplexity() == null) {
            return;
        }

        final var stateToHash = handlerOutput.stateWithHashComplexity().reservedSignedState();
        final var garbageCollector = new DefaultStateGarbageCollector(new NoOpMetrics());
        garbageCollector.registerState(stateToHash.get().reserve("test state garbage collector"));

        if (handlerOutput.stateForPrehandle() != null) {
            handlerOutput.stateForPrehandle().close();
        }
        stateToHash.close();
        garbageCollector.heartbeat();
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
