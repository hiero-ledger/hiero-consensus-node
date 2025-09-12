// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.eventhandling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.state.roster.Roster;
import com.swirlds.common.test.fixtures.Randotron;
import com.swirlds.platform.consensus.SyntheticSnapshot;
import com.swirlds.platform.system.status.actions.FreezePeriodEnteredAction;
import com.swirlds.platform.test.fixtures.addressbook.RandomAddressBookBuilder;
import java.util.List;
import org.hiero.base.crypto.Hash;
import org.hiero.consensus.model.event.ConsensusEvent;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.roster.AddressBook;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.roster.RosterRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link DefaultTransactionHandler}.
 */
class DefaultTransactionHandlerTests {
    private Randotron random;
    private AddressBook addressBook;
    private Roster roster;

    @BeforeEach
    void setUp() {
        random = Randotron.create();
        addressBook = RandomAddressBookBuilder.create(random)
                .withRealKeysEnabled(false)
                .withSize(4)
                .build();
        roster = RosterRetriever.buildRoster(addressBook);
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
                SyntheticSnapshot.getGenesisSnapshot(),
                pcesRound,
                random.nextInstant());

        round.getStreamedEvents().forEach(cesEvent -> cesEvent.getRunningHash().setHash(random.nextHash()));
        return round;
    }

    @DisplayName("Normal operation")
    @ParameterizedTest
    @CsvSource({"false", "true"})
    void normalOperation(final boolean pcesRound) throws InterruptedException {
        final TransactionHandlerTester tester = new TransactionHandlerTester(addressBook);
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

        ArgumentCaptor<Hash> hashCaptor = ArgumentCaptor.forClass(Hash.class);
        verify(tester.getPlatformStateFacade())
                .setLegacyRunningEventHashTo(same(tester.getConsensusState()), hashCaptor.capture());

        assertEquals(
                hashCaptor.getValue(),
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
                        consensusRound, tester.getSwirldStateManager().getConsensusState());
    }

    @Test
    @DisplayName("Round in freeze period")
    void freezeHandling() throws InterruptedException {
        final TransactionHandlerTester tester = new TransactionHandlerTester(addressBook);
        final ConsensusRound consensusRound = newConsensusRound(false);
        when(tester.getPlatformStateFacade().freezeTimeOf(tester.getConsensusState()))
                .thenReturn(consensusRound.getConsensusTimestamp());

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
        verify(tester.getPlatformStateFacade()).updateLastFrozenTime(tester.getConsensusState());

        final ConsensusRound postFreezeConsensusRound = newConsensusRound(false);
        final TransactionHandlerResult postFreezeOutput =
                tester.getTransactionHandler().handleConsensusRound(postFreezeConsensusRound);
        assertNull(postFreezeOutput, "no state should be created after freeze period");

        assertEquals(1, tester.getSubmittedActions().size(), "no new status should have been submitted");
        assertEquals(1, tester.getHandledRounds().size(), "no new rounds should have been handled");
        assertSame(consensusRound, tester.getHandledRounds().getFirst(), "it should same round as before");
        ArgumentCaptor<Hash> hashCaptor = ArgumentCaptor.forClass(Hash.class);
        verify(tester.getPlatformStateFacade())
                .setLegacyRunningEventHashTo(same(tester.getConsensusState()), hashCaptor.capture());

        assertEquals(
                hashCaptor.getValue(),
                consensusRound
                        .getStreamedEvents()
                        .getLast()
                        .getRunningHash()
                        .getFutureHash()
                        .getAndRethrow(),
                "the running hash should from the freeze round");
    }
}
