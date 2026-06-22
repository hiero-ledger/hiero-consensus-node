// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import static org.hiero.consensus.status.logic.StatusLogicTestUtils.triggerActionAndAssertException;
import static org.hiero.consensus.status.logic.StatusLogicTestUtils.triggerActionAndAssertNoTransition;
import static org.hiero.consensus.status.logic.StatusLogicTestUtils.triggerActionAndAssertTransition;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.time.temporal.ChronoUnit;
import org.hiero.consensus.model.status.PlatformStatus;
import org.hiero.consensus.status.PlatformStatusConfig;
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
 * Tests for {@link ReconnectCompleteStatusLogic}.
 */
class ReconnectCompleteStatusLogicTests {
    private FakeTime time;
    private final long reconnectStateRound = 42L;
    private ReconnectCompleteStatusLogic logic;

    @BeforeEach
    void setup() {
        time = new FakeTime();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        logic = new ReconnectCompleteStatusLogic(
                reconnectStateRound, null, configuration.getConfigData(PlatformStatusConfig.class));
    }

    @Test
    @DisplayName("Go to CHECKING when the round written precisely matches the reconnect state round")
    void toCheckingWithPreciseRoundMatch() {
        triggerActionAndAssertTransition(
                logic::processStateWrittenToDiskAction,
                new StateWrittenToDiskAction(reconnectStateRound, false),
                PlatformStatus.CHECKING);
    }

    @Test
    @DisplayName("Go to CHECKING when the round written doesn't precisely match the reconnect state round")
    void toCheckingWithImpreciseRoundMatch() {
        triggerActionAndAssertTransition(
                logic::processStateWrittenToDiskAction,
                new StateWrittenToDiskAction(reconnectStateRound + 3, false),
                PlatformStatus.CHECKING);
    }

    @Test
    @DisplayName("Go to BEHIND")
    void toBehind() {
        triggerActionAndAssertTransition(
                logic::processFallenBehindAction, new FallenBehindAction(), PlatformStatus.BEHIND);
    }

    @Test
    @DisplayName("Go to FREEZING when the round written precisely matches the reconnect state round")
    void toFreezingWithPreciseRoundMatch() {
        triggerActionAndAssertNoTransition(
                logic::processFreezePeriodEnteredAction, new FreezePeriodEnteredAction(0), logic.getStatus());
        triggerActionAndAssertTransition(
                logic::processStateWrittenToDiskAction,
                new StateWrittenToDiskAction(reconnectStateRound, false),
                PlatformStatus.FREEZING);
    }

    @Test
    @DisplayName("Go to FREEZING when the round written doesn't precisely match the reconnect state round")
    void toFreezingWithImpreciseRoundMatch() {
        triggerActionAndAssertNoTransition(
                logic::processFreezePeriodEnteredAction, new FreezePeriodEnteredAction(0), logic.getStatus());
        triggerActionAndAssertTransition(
                logic::processStateWrittenToDiskAction,
                new StateWrittenToDiskAction(reconnectStateRound + 5, false),
                PlatformStatus.FREEZING);
    }

    @Test
    @DisplayName("Go to FREEZING when the logic object was constructed with a non-null freeze boundary")
    void toFreezingWithPriorFreezeBoundary() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        // this logic is being constructed as if the freeze boundary was crossed while in the BEHIND status
        logic = new ReconnectCompleteStatusLogic(
                reconnectStateRound, 10L, configuration.getConfigData(PlatformStatusConfig.class));

        triggerActionAndAssertTransition(
                logic::processStateWrittenToDiskAction,
                new StateWrittenToDiskAction(reconnectStateRound, false),
                PlatformStatus.FREEZING);
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
                logic::processSelfEventReachedConsensusAction,
                new SelfEventReachedConsensusAction(time.now()),
                logic.getStatus());
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
        // if the state written is prior to the reconnect state, it should be ignored
        triggerActionAndAssertNoTransition(
                logic::processStateWrittenToDiskAction,
                new StateWrittenToDiskAction(reconnectStateRound - 1, false),
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
