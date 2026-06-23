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
        assertTransition(logic, new StateWrittenToDiskTrigger(reconnectStateRound, false), PlatformStatus.CHECKING);
    }

    @Test
    @DisplayName("Go to CHECKING when the round written doesn't precisely match the reconnect state round")
    void toCheckingWithImpreciseRoundMatch() {
        assertTransition(logic, new StateWrittenToDiskTrigger(reconnectStateRound + 3, false), PlatformStatus.CHECKING);
    }

    @Test
    @DisplayName("Go to BEHIND")
    void toBehind() {
        assertTransition(logic, new FallenBehindTrigger(), PlatformStatus.BEHIND);
    }

    @Test
    @DisplayName("Go to FREEZING when the round written precisely matches the reconnect state round")
    void toFreezingWithPreciseRoundMatch() {
        assertNoTransition(logic, new FreezePeriodEnteredTrigger(0), logic.getStatus());
        assertTransition(logic, new StateWrittenToDiskTrigger(reconnectStateRound, false), PlatformStatus.FREEZING);
    }

    @Test
    @DisplayName("Go to FREEZING when the round written doesn't precisely match the reconnect state round")
    void toFreezingWithImpreciseRoundMatch() {
        assertNoTransition(logic, new FreezePeriodEnteredTrigger(0), logic.getStatus());
        assertTransition(logic, new StateWrittenToDiskTrigger(reconnectStateRound + 5, false), PlatformStatus.FREEZING);
    }

    @Test
    @DisplayName("Go to FREEZING when the logic object was constructed with a non-null freeze boundary")
    void toFreezingWithPriorFreezeBoundary() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        // this logic is being constructed as if the freeze boundary was crossed while in the BEHIND status
        logic = new ReconnectCompleteStatusLogic(
                reconnectStateRound, 10L, configuration.getConfigData(PlatformStatusConfig.class));

        assertTransition(logic, new StateWrittenToDiskTrigger(reconnectStateRound, false), PlatformStatus.FREEZING);
    }

    @Test
    @DisplayName("Go to CATASTROPHIC_FAILURE")
    void toCatastrophicFailure() {
        assertTransition(logic, new CatastrophicFailureTrigger(), PlatformStatus.CATASTROPHIC_FAILURE);
    }

    @Test
    @DisplayName("Throw exception when receiving duplicate freeze round notification")
    void duplicateFreezeRound() {
        assertNoTransition(logic, new FreezePeriodEnteredTrigger(0), logic.getStatus());
        assertException(logic, new FreezePeriodEnteredTrigger(0), logic.getStatus());
    }

    @Test
    @DisplayName("Go to FREEZE_COMPLETE")
    void toFreezeComplete() {
        assertTransition(logic, new StateWrittenToDiskTrigger(0, true), PlatformStatus.FREEZE_COMPLETE);
    }

    @Test
    @DisplayName("Irrelevant triggers shouldn't cause transitions")
    void irrelevantTriggers() {
        assertNoTransition(logic, new SelfEventReachedConsensusTrigger(time.now()), logic.getStatus());
        assertNoTransition(
                logic,
                new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(false, time.now())),
                logic.getStatus());
        assertNoTransition(
                logic,
                new TimeElapsedTrigger(
                        time.now(),
                        new TimeElapsedTrigger.QuiescingStatus(true, time.now().plus(5, ChronoUnit.MINUTES))),
                logic.getStatus());
        // if the state written is prior to the reconnect state, it should be ignored
        assertNoTransition(logic, new StateWrittenToDiskTrigger(reconnectStateRound - 1, false), logic.getStatus());
    }

    @Test
    @DisplayName("Unexpected triggers should cause exceptions")
    void unexpectedTriggers() {
        assertException(logic, new StartedReplayingEventsTrigger(), logic.getStatus());
        assertException(logic, new DoneReplayingEventsTrigger(time.now()), logic.getStatus());
        assertException(logic, new ReconnectCompleteTrigger(0), logic.getStatus());
    }
}
