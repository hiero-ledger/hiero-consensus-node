// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import static org.hiero.consensus.status.logic.StatusLogicTestUtils.assertException;
import static org.hiero.consensus.status.logic.StatusLogicTestUtils.assertNoTransition;
import static org.hiero.consensus.status.logic.StatusLogicTestUtils.assertTransition;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.time.temporal.ChronoUnit;
import org.hiero.consensus.config.PlatformStatusConfig;
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
 * Tests for {@link BehindStatusLogic}.
 */
class BehindStatusLogicTests {
    private FakeTime time;
    private BehindStatusLogic logic;

    @BeforeEach
    void setup() {
        time = new FakeTime();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        logic = new BehindStatusLogic(configuration.getConfigData(PlatformStatusConfig.class));
    }

    @Test
    @DisplayName("Go to RECONNECT_COMPLETE")
    void toReconnectComplete() {
        assertTransition(logic, new ReconnectCompleteAction(0), PlatformStatus.RECONNECT_COMPLETE);
    }

    @Test
    @DisplayName("Go to CATASTROPHIC_FAILURE")
    void toCatastrophicFailure() {
        assertTransition(logic, new CatastrophicFailureAction(), PlatformStatus.CATASTROPHIC_FAILURE);
    }

    @Test
    @DisplayName("Throw exception when receiving duplicate freeze round notification")
    void duplicateFreezeRound() {
        assertNoTransition(logic, new FreezePeriodEnteredAction(0), logic.getStatus());
        assertException(logic, new FreezePeriodEnteredAction(0), logic.getStatus());
    }

    @Test
    @DisplayName("Go to FREEZE_COMPLETE")
    void toFreezeComplete() {
        assertTransition(logic, new StateWrittenToDiskAction(0, true), PlatformStatus.FREEZE_COMPLETE);
    }

    @Test
    @DisplayName("Irrelevant actions shouldn't cause transitions")
    void irrelevantActions() {
        assertNoTransition(
                logic,
                new TimeElapsedAction(time.now(), new TimeElapsedAction.QuiescingStatus(false, time.now())),
                logic.getStatus());
        assertNoTransition(
                logic,
                new TimeElapsedAction(
                        time.now(),
                        new TimeElapsedAction.QuiescingStatus(true, time.now().minus(5, ChronoUnit.SECONDS))),
                logic.getStatus());
        assertNoTransition(logic, new SelfEventReachedConsensusAction(time.now()), logic.getStatus());
        assertNoTransition(logic, new FreezePeriodEnteredAction(0), logic.getStatus());
        assertNoTransition(logic, new StateWrittenToDiskAction(0, false), logic.getStatus());
    }

    @Test
    @DisplayName("Unexpected actions should cause exceptions")
    void unexpectedActions() {
        assertException(logic, new StartedReplayingEventsAction(), logic.getStatus());
        assertException(logic, new DoneReplayingEventsAction(time.now()), logic.getStatus());
        assertException(logic, new FallenBehindAction(), logic.getStatus());
    }
}
