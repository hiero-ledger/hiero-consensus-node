// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.monitor.logic;

import static org.hiero.consensus.status.monitor.logic.StatusLogicTestUtils.assertNoTransition;

import com.swirlds.base.test.fixtures.time.FakeTime;
import java.time.temporal.ChronoUnit;
import org.hiero.consensus.status.monitor.actions.CatastrophicFailureAction;
import org.hiero.consensus.status.monitor.actions.DoneReplayingEventsAction;
import org.hiero.consensus.status.monitor.actions.FallenBehindAction;
import org.hiero.consensus.status.monitor.actions.FreezePeriodEnteredAction;
import org.hiero.consensus.status.monitor.actions.ReconnectCompleteAction;
import org.hiero.consensus.status.monitor.actions.SelfEventReachedConsensusAction;
import org.hiero.consensus.status.monitor.actions.StartedReplayingEventsAction;
import org.hiero.consensus.status.monitor.actions.StateWrittenToDiskAction;
import org.hiero.consensus.status.monitor.actions.TimeElapsedAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CatastrophicFailureStatusLogic}.
 */
class CatastrophicFailureStatusLogicTests {
    private FakeTime time;
    private CatastrophicFailureStatusLogic logic;

    @BeforeEach
    void setup() {
        time = new FakeTime();
        logic = new CatastrophicFailureStatusLogic();
    }

    @Test
    @DisplayName("Irrelevant actions shouldn't cause transitions")
    void irrelevantActions() {
        assertNoTransition(logic, new StartedReplayingEventsAction(), logic.getStatus());
        assertNoTransition(logic, new DoneReplayingEventsAction(time.now()), logic.getStatus());
        assertNoTransition(logic, new SelfEventReachedConsensusAction(time.now()), logic.getStatus());
        assertNoTransition(logic, new FreezePeriodEnteredAction(0), logic.getStatus());
        assertNoTransition(logic, new FallenBehindAction(), logic.getStatus());
        assertNoTransition(logic, new ReconnectCompleteAction(0), logic.getStatus());
        assertNoTransition(logic, new StateWrittenToDiskAction(0, false), logic.getStatus());
        assertNoTransition(logic, new StateWrittenToDiskAction(0, true), logic.getStatus());
        assertNoTransition(logic, new CatastrophicFailureAction(), logic.getStatus());
        assertNoTransition(
                logic,
                new TimeElapsedAction(time.now(), new TimeElapsedAction.QuiescingStatus(false, time.now())),
                logic.getStatus());
        assertNoTransition(
                logic,
                new TimeElapsedAction(
                        time.now(),
                        new TimeElapsedAction.QuiescingStatus(true, time.now().minus(5, ChronoUnit.MINUTES))),
                logic.getStatus());
    }
}
