// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.status.logic;

import static com.swirlds.platform.system.status.logic.StatusLogicTestUtils.triggerActionAndAssertNoTransition;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.platform.system.status.actions.CatastrophicFailureAction;
import com.swirlds.platform.system.status.actions.DoneReplayingEventsAction;
import com.swirlds.platform.system.status.actions.FallenBehindAction;
import com.swirlds.platform.system.status.actions.FreezePeriodEnteredAction;
import com.swirlds.platform.system.status.actions.ReconnectCompleteAction;
import com.swirlds.platform.system.status.actions.SelfEventReachedConsensusAction;
import com.swirlds.platform.system.status.actions.StartedReplayingEventsAction;
import com.swirlds.platform.system.status.actions.StateWrittenToDiskAction;
import com.swirlds.platform.system.status.actions.TimeElapsedAction;
import java.time.temporal.ChronoUnit;
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
