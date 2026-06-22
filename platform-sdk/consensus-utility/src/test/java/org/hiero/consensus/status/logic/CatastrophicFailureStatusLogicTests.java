// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import static org.hiero.consensus.status.logic.StatusLogicTestUtils.assertNoTransition;

import com.swirlds.base.test.fixtures.time.FakeTime;
import java.time.temporal.ChronoUnit;
import org.hiero.consensus.status.triggers.CatastrophicFailureTrigger;
import org.hiero.consensus.status.triggers.DoneReplayingEventsTrigger;
import org.hiero.consensus.status.triggers.FallenBehindTrigger;
import org.hiero.consensus.status.triggers.FreezePeriodEnteredTrigger;
import org.hiero.consensus.status.triggers.ReconnectCompleteTrigger;
import org.hiero.consensus.status.triggers.SelfEventReachedConsensusTrigger;
import org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger;
import org.hiero.consensus.status.triggers.StateWrittenToDiskTrigger;
import org.hiero.consensus.status.triggers.TimeElapsedTrigger;
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
    @DisplayName("Irrelevant triggers shouldn't cause transitions")
    void irrelevantTriggers() {
        assertNoTransition(logic, new StartedReplayingEventsTrigger(), logic.getStatus());
        assertNoTransition(logic, new DoneReplayingEventsTrigger(time.now()), logic.getStatus());
        assertNoTransition(logic, new SelfEventReachedConsensusTrigger(time.now()), logic.getStatus());
        assertNoTransition(logic, new FreezePeriodEnteredTrigger(0), logic.getStatus());
        assertNoTransition(logic, new FallenBehindTrigger(), logic.getStatus());
        assertNoTransition(logic, new ReconnectCompleteTrigger(0), logic.getStatus());
        assertNoTransition(logic, new StateWrittenToDiskTrigger(0, false), logic.getStatus());
        assertNoTransition(logic, new StateWrittenToDiskTrigger(0, true), logic.getStatus());
        assertNoTransition(logic, new CatastrophicFailureTrigger(), logic.getStatus());
        assertNoTransition(
                logic,
                new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(false, time.now())),
                logic.getStatus());
        assertNoTransition(
                logic,
                new TimeElapsedTrigger(
                        time.now(),
                        new TimeElapsedTrigger.QuiescingStatus(true, time.now().minus(5, ChronoUnit.MINUTES))),
                logic.getStatus());
    }
}
