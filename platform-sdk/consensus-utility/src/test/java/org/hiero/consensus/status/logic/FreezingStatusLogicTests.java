// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import static org.hiero.consensus.status.logic.StatusLogicTestUtils.assertException;
import static org.hiero.consensus.status.logic.StatusLogicTestUtils.assertNoTransition;
import static org.hiero.consensus.status.logic.StatusLogicTestUtils.assertTransition;

import com.swirlds.base.test.fixtures.time.FakeTime;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.actions.CatastrophicFailureAction;
import org.hiero.consensus.status.actions.DoneReplayingEventsAction;
import org.hiero.consensus.status.actions.FallenBehindAction;
import org.hiero.consensus.status.actions.FreezePeriodEnteredAction;
import org.hiero.consensus.status.actions.ReconnectCompleteAction;
import org.hiero.consensus.status.actions.SelfEventReachedConsensusAction;
import org.hiero.consensus.status.actions.StartedReplayingEventsAction;
import org.hiero.consensus.status.actions.StateWrittenToDiskAction;
import org.hiero.consensus.status.actions.TimeElapsedAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FreezingStatusLogic}.
 */
class FreezingStatusLogicTests {
    private final long testFreezeRound = 42L;
    private FakeTime time;
    private FreezingStatusLogic logic;

    @BeforeEach
    void setup() {
        time = new FakeTime();
        logic = new FreezingStatusLogic(testFreezeRound);
    }

    @Test
    @DisplayName("Go to CATASTROPHIC_FAILURE")
    void toCatastrophicFailure() {
        assertTransition(logic, new CatastrophicFailureAction(), PlatformStatus.CATASTROPHIC_FAILURE);
    }

    @Test
    @DisplayName("Go to FREEZE_COMPLETE")
    void toFreezeComplete() {
        assertTransition(logic, new StateWrittenToDiskAction(testFreezeRound, true), PlatformStatus.FREEZE_COMPLETE);
    }

    @Test
    @DisplayName("Irrelevant actions shouldn't cause transitions")
    void irrelevantActions() {
        assertNoTransition(logic, new SelfEventReachedConsensusAction(time.now()), logic.getStatus());
        assertNoTransition(logic, new FallenBehindAction(), logic.getStatus());
        assertNoTransition(
                logic,
                new TimeElapsedAction(time.now(), new TimeElapsedAction.QuiescingStatus(false, time.now())),
                logic.getStatus());
        assertNoTransition(logic, new StateWrittenToDiskAction(0, false), logic.getStatus());
    }

    @Test
    @DisplayName("Unexpected actions should cause exceptions")
    void unexpectedActions() {
        assertException(logic, new StartedReplayingEventsAction(), logic.getStatus());
        assertException(logic, new DoneReplayingEventsAction(time.now()), logic.getStatus());
        assertException(logic, new FreezePeriodEnteredAction(0), logic.getStatus());
        assertException(logic, new ReconnectCompleteAction(0), logic.getStatus());
    }
}
