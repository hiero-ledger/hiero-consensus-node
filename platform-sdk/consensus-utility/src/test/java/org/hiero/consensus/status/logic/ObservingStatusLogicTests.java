// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import static org.hiero.consensus.status.logic.StatusLogicTestUtils.triggerActionAndAssertException;
import static org.hiero.consensus.status.logic.StatusLogicTestUtils.triggerActionAndAssertNoTransition;
import static org.hiero.consensus.status.logic.StatusLogicTestUtils.triggerActionAndAssertTransition;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.time.Duration;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.PlatformStatusConfig;
import org.hiero.consensus.status.PlatformStatusConfig_;
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
 * Tests for {@link ObservingStatusLogic}.
 */
class ObservingStatusLogicTests {
    private FakeTime time;
    private ObservingStatusLogic logic;

    @BeforeEach
    void setup() {
        time = new FakeTime();
        final Configuration configuration = new TestConfigBuilder()
                .withValue(PlatformStatusConfig_.OBSERVING_STATUS_DELAY, "5s")
                .getOrCreateConfig();
        logic = new ObservingStatusLogic(time.now(), configuration.getConfigData(PlatformStatusConfig.class));
    }

    @Test
    @DisplayName("Go to FREEZING")
    void toFreezing() {
        triggerActionAndAssertNoTransition(
                logic::processFreezePeriodEnteredAction, new FreezePeriodEnteredAction(0), logic.getStatus());

        time.tick(Duration.ofSeconds(2));
        triggerActionAndAssertNoTransition(
                logic::processTimeElapsedAction,
                new TimeElapsedAction(time.now(), new TimeElapsedAction.QuiescingStatus(false, time.now())),
                logic.getStatus());

        time.tick(Duration.ofSeconds(4));
        triggerActionAndAssertTransition(
                logic::processTimeElapsedAction,
                new TimeElapsedAction(time.now(), new TimeElapsedAction.QuiescingStatus(false, time.now())),
                PlatformStatus.FREEZING);
    }

    @Test
    @DisplayName("Go to CHECKING")
    void toChecking() {
        triggerActionAndAssertNoTransition(
                logic::processTimeElapsedAction,
                new TimeElapsedAction(time.now(), new TimeElapsedAction.QuiescingStatus(false, time.now())),
                logic.getStatus());

        time.tick(Duration.ofSeconds(3));
        triggerActionAndAssertNoTransition(
                logic::processTimeElapsedAction,
                new TimeElapsedAction(time.now(), new TimeElapsedAction.QuiescingStatus(false, time.now())),
                logic.getStatus());

        time.tick(Duration.ofSeconds(3));
        triggerActionAndAssertTransition(
                logic::processTimeElapsedAction,
                new TimeElapsedAction(time.now(), new TimeElapsedAction.QuiescingStatus(false, time.now())),
                PlatformStatus.CHECKING);
    }

    @Test
    @DisplayName("Go to BEHIND")
    void toBehind() {
        triggerActionAndAssertTransition(
                logic::processFallenBehindAction, new FallenBehindAction(), PlatformStatus.BEHIND);
    }

    @Test
    @DisplayName("Go to CATASTROPHIC_FAILURE")
    void toCatastrophicFailure() {
        triggerActionAndAssertTransition(
                logic::processCatastrophicFailureAction,
                new CatastrophicFailureAction(),
                PlatformStatus.CATASTROPHIC_FAILURE);
    }

    @Test
    @DisplayName("Throw exception when receiving duplicate freeze round notification")
    void duplicateFreezeRound() {
        triggerActionAndAssertNoTransition(
                logic::processFreezePeriodEnteredAction, new FreezePeriodEnteredAction(0), logic.getStatus());
        triggerActionAndAssertException(
                logic::processFreezePeriodEnteredAction, new FreezePeriodEnteredAction(0), logic.getStatus());
    }

    @Test
    @DisplayName("Go to FREEZE_COMPLETE")
    void toFreezeComplete() {
        triggerActionAndAssertTransition(
                logic::processStateWrittenToDiskAction,
                new StateWrittenToDiskAction(0, true),
                PlatformStatus.FREEZE_COMPLETE);
    }

    @Test
    @DisplayName("Irrelevant actions shouldn't cause transitions")
    void irrelevantActions() {
        triggerActionAndAssertNoTransition(
                logic::processStateWrittenToDiskAction, new StateWrittenToDiskAction(0, false), logic.getStatus());
        triggerActionAndAssertNoTransition(
                logic::processSelfEventReachedConsensusAction,
                new SelfEventReachedConsensusAction(time.now()),
                logic.getStatus());
    }

    @Test
    @DisplayName("Unexpected actions should cause exceptions")
    void unexpectedActions() {
        triggerActionAndAssertException(
                logic::processStartedReplayingEventsAction, new StartedReplayingEventsAction(), logic.getStatus());
        triggerActionAndAssertException(
                logic::processDoneReplayingEventsAction, new DoneReplayingEventsAction(time.now()), logic.getStatus());
        triggerActionAndAssertException(
                logic::processReconnectCompleteAction, new ReconnectCompleteAction(0), logic.getStatus());
    }
}
