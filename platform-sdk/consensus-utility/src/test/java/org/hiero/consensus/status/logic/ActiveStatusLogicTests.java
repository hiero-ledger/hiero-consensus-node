// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import static org.hiero.consensus.status.logic.StatusLogicTestUtils.assertException;
import static org.hiero.consensus.status.logic.StatusLogicTestUtils.assertNoTransition;
import static org.hiero.consensus.status.logic.StatusLogicTestUtils.assertTransition;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.hiero.consensus.config.PlatformStatusConfig;
import org.hiero.consensus.config.PlatformStatusConfig_;
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
import org.hiero.consensus.status.triggers.TimeElapsedTrigger.QuiescingStatus;
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
        assertTransition(logic, new FreezePeriodEnteredTrigger(0), PlatformStatus.FREEZING);
    }

    @Test
    @DisplayName("Go to CHECKING")
    void toChecking() {
        final QuiescingStatus neutralQuiescingStatus =
                new QuiescingStatus(false, time.now().minus(Duration.of(1, ChronoUnit.HOURS)));
        assertNoTransition(logic, new TimeElapsedTrigger(time.now(), neutralQuiescingStatus), logic.getStatus());

        time.tick(Duration.ofSeconds(2));
        assertNoTransition(logic, new TimeElapsedTrigger(time.now(), neutralQuiescingStatus), logic.getStatus());

        // restart the timer that will trigger the status change to checking
        assertNoTransition(logic, new SelfEventReachedConsensusTrigger(time.now()), logic.getStatus());

        // if the self event reaching consensus successfully restarted the timer, then the status should still be active
        time.tick(Duration.ofSeconds(4));
        assertNoTransition(logic, new TimeElapsedTrigger(time.now(), neutralQuiescingStatus), logic.getStatus());

        time.tick(Duration.ofSeconds(2));
        assertTransition(logic, new TimeElapsedTrigger(time.now(), neutralQuiescingStatus), PlatformStatus.CHECKING);
    }

    @Test
    @DisplayName("Go to BEHIND")
    void toBehind() {
        assertTransition(logic, new FallenBehindTrigger(), PlatformStatus.BEHIND);
    }

    @Test
    @DisplayName("Go to CATASTROPHIC_FAILURE")
    void toCatastrophicFailure() {
        assertTransition(logic, new CatastrophicFailureTrigger(), PlatformStatus.CATASTROPHIC_FAILURE);
    }

    @Test
    @DisplayName("Go to FREEZE_COMPLETE")
    void toFreezeComplete() {
        assertTransition(logic, new StateWrittenToDiskTrigger(0, true), PlatformStatus.FREEZE_COMPLETE);
    }

    @Test
    @DisplayName("Irrelevant triggers shouldn't cause transitions")
    void irrelevantTriggers() {
        assertNoTransition(logic, new StateWrittenToDiskTrigger(0, false), logic.getStatus());
    }

    @Test
    @DisplayName("Unexpected triggers should cause exceptions")
    void unexpectedTriggers() {
        assertException(logic, new ReconnectCompleteTrigger(0), logic.getStatus());
        assertException(logic, new DoneReplayingEventsTrigger(time.now()), logic.getStatus());
        assertException(logic, new StartedReplayingEventsTrigger(), logic.getStatus());
    }

    @Test
    @DisplayName("Remain ACTIVE when quiescing")
    void remainActiveWhenQuiescing() {
        // Even with time elapsed, should remain ACTIVE when quiescing
        assertNoTransition(
                logic, new TimeElapsedTrigger(time.now(), new QuiescingStatus(true, time.now())), logic.getStatus());
    }

    @Test
    @DisplayName("Remain ACTIVE when insufficient time since quiescence command")
    void remainActiveWhenInsufficientTimeSinceQuiescenceCommand() {
        final Instant quiescenceActiveTime = time.now();
        time.tick(Duration.ofSeconds(2));
        // Should remain ACTIVE when not enough time has passed since quiescence command (2s < 5s delay)
        assertNoTransition(
                logic,
                new TimeElapsedTrigger(time.now(), new QuiescingStatus(false, quiescenceActiveTime)),
                logic.getStatus());
    }

    @Test
    @DisplayName("Go to CHECKING when sufficient time since quiescence command")
    void toCheckingWhenSufficientTimeSinceQuiescenceCommand() {
        final QuiescingStatus oldQuiescenceStatus = new QuiescingStatus(false, time.now());
        time.tick(Duration.ofSeconds(6));
        // Should transition to CHECKING when enough time has passed since both quiescence command and consensus
        assertTransition(logic, new TimeElapsedTrigger(time.now(), oldQuiescenceStatus), PlatformStatus.CHECKING);
    }

    @Test
    @DisplayName("Go to CHECKING when sufficient time since quiescence command and consensus")
    void toCheckingWhenSufficientTimeSinceBothQuiescenceCommandAndConsensus() {
        final QuiescingStatus oldQuiescenceStatus = new QuiescingStatus(false, time.now());
        assertNoTransition(logic, new SelfEventReachedConsensusTrigger(time.now()), PlatformStatus.ACTIVE);
        time.tick(Duration.ofSeconds(6));
        // Should transition to CHECKING when enough time has passed since both quiescence command and consensus
        assertTransition(logic, new TimeElapsedTrigger(time.now(), oldQuiescenceStatus), PlatformStatus.CHECKING);
    }
}
