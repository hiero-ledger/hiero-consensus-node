// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.system.status.logic;

import static com.swirlds.platform.system.status.logic.StatusLogicTestUtils.triggerActionAndAssertException;
import static com.swirlds.platform.system.status.logic.StatusLogicTestUtils.triggerActionAndAssertNoTransition;
import static com.swirlds.platform.system.status.logic.StatusLogicTestUtils.triggerActionAndAssertTransition;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.platform.system.status.PlatformStatusConfig;
import com.swirlds.platform.system.status.PlatformStatusConfig_;
import com.swirlds.platform.system.status.actions.CatastrophicFailureAction;
import com.swirlds.platform.system.status.actions.DoneReplayingEventsAction;
import com.swirlds.platform.system.status.actions.FallenBehindAction;
import com.swirlds.platform.system.status.actions.FreezePeriodEnteredAction;
import com.swirlds.platform.system.status.actions.ReconnectCompleteAction;
import com.swirlds.platform.system.status.actions.SelfEventReachedConsensusAction;
import com.swirlds.platform.system.status.actions.StartedReplayingEventsAction;
import com.swirlds.platform.system.status.actions.StateWrittenToDiskAction;
import com.swirlds.platform.system.status.actions.TimeElapsedAction;
import com.swirlds.platform.system.status.actions.TimeElapsedAction.QuiescingStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.hiero.consensus.model.status.PlatformStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ActiveStatusLogic}.
 */
class ActiveStatusLogicTests {
    private FakeTime time;
    private ActiveStatusLogic logic;
    private Configuration configuration;

    @BeforeEach
    void setup() {
        time = new FakeTime();
        configuration = new TestConfigBuilder()
                .withValue(PlatformStatusConfig_.ACTIVE_STATUS_DELAY, "5s")
                .getOrCreateConfig();
        logic = new ActiveStatusLogic(time.now(), configuration.getConfigData(PlatformStatusConfig.class));
    }

    @Test
    @DisplayName("Go to FREEZING")
    void toFreezing() {
        triggerActionAndAssertTransition(
                logic::processFreezePeriodEnteredAction, new FreezePeriodEnteredAction(0), PlatformStatus.FREEZING);
    }

    @Test
    @DisplayName("Go to CHECKING")
    void toChecking() {
        final QuiescingStatus neutralQuiescingStatus =
                new QuiescingStatus(false, time.now().minus(Duration.of(1, ChronoUnit.HOURS)));
        triggerActionAndAssertNoTransition(
                logic::processTimeElapsedAction,
                new TimeElapsedAction(time.now(), neutralQuiescingStatus),
                logic.getStatus());

        time.tick(Duration.ofSeconds(2));
        triggerActionAndAssertNoTransition(
                logic::processTimeElapsedAction,
                new TimeElapsedAction(time.now(), neutralQuiescingStatus),
                logic.getStatus());

        // restart the timer that will trigger the status change to checking
        triggerActionAndAssertNoTransition(
                logic::processSelfEventReachedConsensusAction,
                new SelfEventReachedConsensusAction(time.now()),
                logic.getStatus());

        // if the self event reaching consensus successfully restarted the timer, then the status should still be active
        time.tick(Duration.ofSeconds(4));
        triggerActionAndAssertNoTransition(
                logic::processTimeElapsedAction,
                new TimeElapsedAction(time.now(), neutralQuiescingStatus),
                logic.getStatus());

        time.tick(Duration.ofSeconds(2));
        triggerActionAndAssertTransition(
                logic::processTimeElapsedAction,
                new TimeElapsedAction(time.now(), neutralQuiescingStatus),
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
    }

    @Test
    @DisplayName("Unexpected actions should cause exceptions")
    void unexpectedActions() {
        triggerActionAndAssertException(
                logic::processReconnectCompleteAction, new ReconnectCompleteAction(0), logic.getStatus());
        triggerActionAndAssertException(
                logic::processDoneReplayingEventsAction, new DoneReplayingEventsAction(time.now()), logic.getStatus());
        triggerActionAndAssertException(
                logic::processStartedReplayingEventsAction, new StartedReplayingEventsAction(), logic.getStatus());
    }

    @Test
    @DisplayName("Remain ACTIVE when quiescing")
    void remainActiveWhenQuiescing() {
        // Even with time elapsed, should remain ACTIVE when quiescing
        triggerActionAndAssertNoTransition(
                logic::processTimeElapsedAction,
                new TimeElapsedAction(time.now(), new QuiescingStatus(true, time.now())),
                logic.getStatus());
    }

    @Test
    @DisplayName("Remain ACTIVE when insufficient time since quiescence command")
    void remainActiveWhenInsufficientTimeSinceQuiescenceCommand() {
        final Instant quiescenceActiveTime = time.now();
        time.tick(Duration.ofSeconds(2));
        // Should remain ACTIVE when not enough time has passed since quiescence command (2s < 5s delay)
        triggerActionAndAssertNoTransition(
                logic::processTimeElapsedAction,
                new TimeElapsedAction(time.now(), new QuiescingStatus(false, quiescenceActiveTime)),
                logic.getStatus());
    }

    @Test
    @DisplayName("Go to CHECKING when sufficient time since quiescence command")
    void toCheckingWhenSufficientTimeSinceQuiescenceCommand() {
        final QuiescingStatus oldQuiescenceStatus = new QuiescingStatus(false, time.now());
        time.tick(Duration.ofSeconds(6));
        // Should transition to CHECKING when enough time has passed since both quiescence command and consensus
        triggerActionAndAssertTransition(
                logic::processTimeElapsedAction,
                new TimeElapsedAction(time.now(), oldQuiescenceStatus),
                PlatformStatus.CHECKING);
    }

    @Test
    @DisplayName("Go to CHECKING when sufficient time since quiescence command and consensus")
    void toCheckingWhenSufficientTimeSinceBothQuiescenceCommandAndConsensus() {
        final QuiescingStatus oldQuiescenceStatus = new QuiescingStatus(false, time.now());
        triggerActionAndAssertNoTransition(
                logic::processSelfEventReachedConsensusAction,
                new SelfEventReachedConsensusAction(time.now()),
                PlatformStatus.ACTIVE);
        time.tick(Duration.ofSeconds(6));
        // Should transition to CHECKING when enough time has passed since both quiescence command and consensus
        triggerActionAndAssertTransition(
                logic::processTimeElapsedAction,
                new TimeElapsedAction(time.now(), oldQuiescenceStatus),
                PlatformStatus.CHECKING);
    }
}
