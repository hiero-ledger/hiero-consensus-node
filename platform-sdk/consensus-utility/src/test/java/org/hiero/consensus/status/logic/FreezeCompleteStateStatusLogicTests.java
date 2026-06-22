// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import static org.hiero.consensus.status.logic.StatusLogicTestUtils.triggerActionAndAssertNoTransition;

import com.swirlds.base.test.fixtures.time.FakeTime;
import java.time.temporal.ChronoUnit;
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
 * Tests for {@link FreezeCompleteStatusLogic}.
 */
class FreezeCompleteStateStatusLogicTests {
    private FakeTime time;
    private FreezeCompleteStatusLogic logic;

    @BeforeEach
    void setup() {
        time = new FakeTime();
        logic = new FreezeCompleteStatusLogic();
    }

    @Test
    @DisplayName("Irrelevant actions shouldn't cause transitions")
    void irrelevantActions() {
        triggerActionAndAssertNoTransition(
                logic::processStartedReplayingEventsAction, new StartedReplayingEventsAction(), logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processDoneReplayingEventsAction, new DoneReplayingEventsAction(time.now()), logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processSelfEventReachedConsensusAction,
                new SelfEventReachedConsensusAction(time.now()),
                logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processFreezePeriodEnteredAction, new FreezePeriodEnteredAction(0), logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processFallenBehindAction, new FallenBehindAction(), logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processReconnectCompleteAction, new ReconnectCompleteAction(0), logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processStateWrittenToDiskAction, new StateWrittenToDiskAction(0, false), logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processStateWrittenToDiskAction, new StateWrittenToDiskAction(0, true), logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processCatastrophicFailureAction, new CatastrophicFailureAction(), logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processTimeElapsedAction,
                new TimeElapsedAction(time.now(), new TimeElapsedAction.QuiescingStatus(false, time.now())),
                logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processTimeElapsedAction,
                new TimeElapsedAction(
                        time.now(),
                        new TimeElapsedAction.QuiescingStatus(true, time.now().plus(5, ChronoUnit.MINUTES))),
                logic.getStatus());
    }
}
