// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status;

import static org.hiero.consensus.model.status.PlatformStatus.ACTIVE;
import static org.hiero.consensus.model.status.PlatformStatus.BEHIND;
import static org.hiero.consensus.model.status.PlatformStatus.CATASTROPHIC_FAILURE;
import static org.hiero.consensus.model.status.PlatformStatus.CHECKING;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZE_COMPLETE;
import static org.hiero.consensus.model.status.PlatformStatus.FREEZING;
import static org.hiero.consensus.model.status.PlatformStatus.OBSERVING;
import static org.hiero.consensus.model.status.PlatformStatus.RECONNECT_COMPLETE;
import static org.hiero.consensus.model.status.PlatformStatus.REPLAYING_EVENTS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.time.Duration;
import org.hiero.consensus.config.PlatformStatusConfig_;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.status.triggers.CatastrophicFailureTrigger;
import org.hiero.consensus.status.triggers.DoneReplayingEventsTrigger;
import org.hiero.consensus.status.triggers.FallenBehindTrigger;
import org.hiero.consensus.status.triggers.FreezePeriodEnteredTrigger;
import org.hiero.consensus.status.triggers.ReconnectCompleteTrigger;
import org.hiero.consensus.status.triggers.SelfEventReachedConsensusTrigger;
import org.hiero.consensus.status.triggers.StateWrittenToDiskTrigger;
import org.hiero.consensus.status.triggers.TimeElapsedTrigger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Traverses every edge of the state machine
 */
class PlatformStatusStateMachineTests {
    private FakeTime time;
    private final boolean quiescing = false;
    private StatusStateMachine stateMachine;

    @BeforeEach
    void setup() {
        time = new FakeTime();
        final Configuration configuration = new TestConfigBuilder()
                .withValue(PlatformStatusConfig_.OBSERVING_STATUS_DELAY, "5s")
                .withValue(PlatformStatusConfig_.ACTIVE_STATUS_DELAY, "10s")
                .getOrCreateConfig();

        stateMachine = new StatusStateMachine(configuration, new NoOpMetrics(), time);
    }

    @Test
    @DisplayName("STARTING_UP -> REPLAYING_EVENTS -> FREEZE_COMPLETE")
    void freezeCompleteAfterReplayingEvents() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertNull(stateMachine.submitTrigger(new FreezePeriodEnteredTrigger(2)));
        assertEquals(FREEZE_COMPLETE, stateMachine.submitTrigger(new StateWrittenToDiskTrigger(2, true)));
    }

    @Test
    @DisplayName("STARTING_UP -> REPLAYING_EVENTS -> OBSERVING -> FREEZE_COMPLETE")
    void freezeCompleteAfterObserving() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        assertEquals(FREEZE_COMPLETE, stateMachine.submitTrigger(new StateWrittenToDiskTrigger(2, true)));
    }

    @Test
    @DisplayName("STARTING_UP -> REPLAYING_EVENTS -> OBSERVING -> FREEZING -> FREEZE_COMPLETE")
    void freezeCompleteAfterFreezing() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        assertNull(stateMachine.submitTrigger(new FreezePeriodEnteredTrigger(2)));
        time.tick(Duration.ofSeconds(6));
        assertEquals(
                FREEZING,
                stateMachine.submitTrigger(new TimeElapsedTrigger(
                        time.now(), new TimeElapsedTrigger.QuiescingStatus(quiescing, time.now()))));
        assertEquals(FREEZE_COMPLETE, stateMachine.submitTrigger(new StateWrittenToDiskTrigger(2, true)));
    }

    @Test
    @DisplayName("STARTING_UP -> REPLAYING_EVENTS -> OBSERVING -> CHECKING -> FREEZE_COMPLETE")
    void freezeCompleteAfterChecking() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        time.tick(Duration.ofSeconds(6));
        assertEquals(
                CHECKING,
                stateMachine.submitTrigger(new TimeElapsedTrigger(
                        time.now(), new TimeElapsedTrigger.QuiescingStatus(quiescing, time.now()))));
        assertEquals(FREEZE_COMPLETE, stateMachine.submitTrigger(new StateWrittenToDiskTrigger(2, true)));
    }

    @Test
    @DisplayName("STARTING_UP -> REPLAYING_EVENTS -> OBSERVING -> CHECKING -> FREEZING")
    void freezingAfterChecking() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        time.tick(Duration.ofSeconds(6));
        assertEquals(
                CHECKING,
                stateMachine.submitTrigger(new TimeElapsedTrigger(
                        time.now(), new TimeElapsedTrigger.QuiescingStatus(quiescing, time.now()))));
        assertEquals(FREEZING, stateMachine.submitTrigger(new FreezePeriodEnteredTrigger(2)));
    }

    @Test
    @DisplayName("STARTING_UP -> REPLAYING_EVENTS -> OBSERVING -> CHECKING -> ACTIVE -> FREEZE_COMPLETE")
    void freezeCompleteAfterActive() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        time.tick(Duration.ofSeconds(6));
        assertEquals(
                CHECKING,
                stateMachine.submitTrigger(new TimeElapsedTrigger(
                        time.now(), new TimeElapsedTrigger.QuiescingStatus(quiescing, time.now()))));
        assertEquals(ACTIVE, stateMachine.submitTrigger(new SelfEventReachedConsensusTrigger(time.now())));
        assertEquals(FREEZE_COMPLETE, stateMachine.submitTrigger(new StateWrittenToDiskTrigger(2, true)));
    }

    @Test
    @DisplayName("STARTING_UP -> REPLAYING_EVENTS -> OBSERVING -> CHECKING -> ACTIVE -> CHECKING")
    void checkingAfterActive() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        time.tick(Duration.ofSeconds(6));
        assertEquals(
                CHECKING,
                stateMachine.submitTrigger(new TimeElapsedTrigger(
                        time.now(), new TimeElapsedTrigger.QuiescingStatus(quiescing, time.now()))));
        assertEquals(ACTIVE, stateMachine.submitTrigger(new SelfEventReachedConsensusTrigger(time.now())));
        final var before = time.now();
        time.tick(Duration.ofSeconds(11));
        assertEquals(
                CHECKING,
                stateMachine.submitTrigger(
                        new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(quiescing, before))));
    }

    @Test
    @DisplayName("STARTING_UP -> REPLAYING_EVENTS -> OBSERVING -> CHECKING -> ACTIVE -> BEHIND")
    void behindAfterActive() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        time.tick(Duration.ofSeconds(6));
        assertEquals(
                CHECKING,
                stateMachine.submitTrigger(new TimeElapsedTrigger(
                        time.now(), new TimeElapsedTrigger.QuiescingStatus(quiescing, time.now()))));
        assertEquals(ACTIVE, stateMachine.submitTrigger(new SelfEventReachedConsensusTrigger(time.now())));
        assertEquals(BEHIND, stateMachine.submitTrigger(new FallenBehindTrigger()));
    }

    @Test
    @DisplayName("STARTING_UP -> REPLAYING_EVENTS -> OBSERVING -> CHECKING -> ACTIVE -> FREEZING")
    void freezingAfterActive() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        time.tick(Duration.ofSeconds(6));
        assertEquals(
                CHECKING,
                stateMachine.submitTrigger(new TimeElapsedTrigger(
                        time.now(), new TimeElapsedTrigger.QuiescingStatus(quiescing, time.now()))));
        assertEquals(ACTIVE, stateMachine.submitTrigger(new SelfEventReachedConsensusTrigger(time.now())));
        assertEquals(FREEZING, stateMachine.submitTrigger(new FreezePeriodEnteredTrigger(2)));
    }

    @Test
    @DisplayName("STARTING_UP -> REPLAYING_EVENTS -> OBSERVING -> CHECKING -> BEHIND")
    void behindAfterChecking() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        time.tick(Duration.ofSeconds(6));
        assertEquals(
                CHECKING,
                stateMachine.submitTrigger(new TimeElapsedTrigger(
                        time.now(), new TimeElapsedTrigger.QuiescingStatus(quiescing, time.now()))));
        assertEquals(BEHIND, stateMachine.submitTrigger(new FallenBehindTrigger()));
    }

    @Test
    @DisplayName("STARTING_UP -> REPLAYING_EVENTS -> OBSERVING -> BEHIND -> FREEZE_COMPLETE")
    void freezeCompleteAfterBehind() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        assertEquals(BEHIND, stateMachine.submitTrigger(new FallenBehindTrigger()));
        assertEquals(FREEZE_COMPLETE, stateMachine.submitTrigger(new StateWrittenToDiskTrigger(2, true)));
    }

    @Test
    @DisplayName("STARTING_UP -> REPLAYING_EVENTS -> OBSERVING -> BEHIND -> RECONNECT_COMPLETE -> FREEZE_COMPLETE")
    void freezeCompleteAfterReconnectComplete() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        assertEquals(BEHIND, stateMachine.submitTrigger(new FallenBehindTrigger()));
        assertEquals(RECONNECT_COMPLETE, stateMachine.submitTrigger(new ReconnectCompleteTrigger(5)));
        assertEquals(FREEZE_COMPLETE, stateMachine.submitTrigger(new StateWrittenToDiskTrigger(2, true)));
    }

    @Test
    @DisplayName("STARTING_UP -> REPLAYING_EVENTS -> OBSERVING -> BEHIND -> RECONNECT_COMPLETE -> BEHIND")
    void behindAfterReconnectComplete() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        assertEquals(BEHIND, stateMachine.submitTrigger(new FallenBehindTrigger()));
        assertEquals(RECONNECT_COMPLETE, stateMachine.submitTrigger(new ReconnectCompleteTrigger(5)));
        assertEquals(BEHIND, stateMachine.submitTrigger(new FallenBehindTrigger()));
    }

    @Test
    @DisplayName("STARTING_UP -> REPLAYING_EVENTS -> OBSERVING -> BEHIND -> RECONNECT_COMPLETE -> FREEZING")
    void freezingAfterReconnectComplete() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        assertEquals(BEHIND, stateMachine.submitTrigger(new FallenBehindTrigger()));
        assertEquals(RECONNECT_COMPLETE, stateMachine.submitTrigger(new ReconnectCompleteTrigger(5)));
        assertNull(stateMachine.submitTrigger(new FreezePeriodEnteredTrigger(10)));
        assertEquals(FREEZING, stateMachine.submitTrigger(new StateWrittenToDiskTrigger(11, false)));
    }

    @Test
    @DisplayName("STARTING_UP -> REPLAYING_EVENTS -> OBSERVING -> BEHIND -> RECONNECT_COMPLETE -> CHECKING")
    void checkingAfterReconnectComplete() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        assertEquals(BEHIND, stateMachine.submitTrigger(new FallenBehindTrigger()));
        assertEquals(RECONNECT_COMPLETE, stateMachine.submitTrigger(new ReconnectCompleteTrigger(5)));
        assertEquals(CHECKING, stateMachine.submitTrigger(new StateWrittenToDiskTrigger(11, false)));
    }

    @Test
    @DisplayName("STARTING_UP -> CATASTROPHIC_FAILURE")
    void startingUpToCatastrophicFailure() {
        assertEquals(CATASTROPHIC_FAILURE, stateMachine.submitTrigger(new CatastrophicFailureTrigger()));
    }

    @Test
    @DisplayName("REPLAYING_EVENTS -> CATASTROPHIC_FAILURE")
    void replayingEventsToCatastrophicFailure() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(CATASTROPHIC_FAILURE, stateMachine.submitTrigger(new CatastrophicFailureTrigger()));
    }

    @Test
    @DisplayName("OBSERVING -> CATASTROPHIC_FAILURE")
    void observingToCatastrophicFailure() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        assertEquals(CATASTROPHIC_FAILURE, stateMachine.submitTrigger(new CatastrophicFailureTrigger()));
    }

    @Test
    @DisplayName("CHECKING -> CATASTROPHIC_FAILURE")
    void checkingToCatastrophicFailure() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        time.tick(Duration.ofSeconds(6));
        assertEquals(
                CHECKING,
                stateMachine.submitTrigger(new TimeElapsedTrigger(
                        time.now(), new TimeElapsedTrigger.QuiescingStatus(quiescing, time.now()))));
        assertEquals(CATASTROPHIC_FAILURE, stateMachine.submitTrigger(new CatastrophicFailureTrigger()));
    }

    @Test
    @DisplayName("RECONNECT_COMPLETE -> CATASTROPHIC_FAILURE")
    void reconnectCompleteToCatastrophicFailure() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        assertEquals(BEHIND, stateMachine.submitTrigger(new FallenBehindTrigger()));
        assertEquals(RECONNECT_COMPLETE, stateMachine.submitTrigger(new ReconnectCompleteTrigger(5)));
        assertEquals(CATASTROPHIC_FAILURE, stateMachine.submitTrigger(new CatastrophicFailureTrigger()));
    }

    @Test
    @DisplayName("ACTIVE -> CATASTROPHIC_FAILURE")
    void activeToCatastrophicFailure() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        time.tick(Duration.ofSeconds(6));
        assertEquals(
                CHECKING,
                stateMachine.submitTrigger(new TimeElapsedTrigger(
                        time.now(), new TimeElapsedTrigger.QuiescingStatus(quiescing, time.now()))));
        assertEquals(ACTIVE, stateMachine.submitTrigger(new SelfEventReachedConsensusTrigger(time.now())));
        assertEquals(CATASTROPHIC_FAILURE, stateMachine.submitTrigger(new CatastrophicFailureTrigger()));
    }

    @Test
    @DisplayName("BEHIND -> CATASTROPHIC_FAILURE")
    void behindToCatastrophicFailure() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        assertEquals(BEHIND, stateMachine.submitTrigger(new FallenBehindTrigger()));
        assertEquals(CATASTROPHIC_FAILURE, stateMachine.submitTrigger(new CatastrophicFailureTrigger()));
    }

    @Test
    @DisplayName("FREEZING -> CATASTROPHIC_FAILURE")
    void freezingToCatastrophicFailure() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        assertNull(stateMachine.submitTrigger(new FreezePeriodEnteredTrigger(2)));
        time.tick(Duration.ofSeconds(6));
        assertEquals(
                FREEZING,
                stateMachine.submitTrigger(new TimeElapsedTrigger(
                        time.now(), new TimeElapsedTrigger.QuiescingStatus(quiescing, time.now()))));
        assertEquals(CATASTROPHIC_FAILURE, stateMachine.submitTrigger(new CatastrophicFailureTrigger()));
    }

    @Test
    @DisplayName("Illegal trigger")
    void illegalTrigger() {
        // state machine must be robust to unexpected triggers
        assertNull(stateMachine.submitTrigger(new FallenBehindTrigger()));
    }

    @Test
    @DisplayName("CHECKING -> ACTIVE (quiescing)")
    void checkingToActiveWhenQuiescing() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        time.tick(Duration.ofSeconds(6));
        assertEquals(
                CHECKING,
                stateMachine.submitTrigger(
                        new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(false, time.now()))));
        // When quiescing, should transition to ACTIVE
        assertEquals(
                ACTIVE,
                stateMachine.submitTrigger(
                        new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(true, time.now()))));
    }

    @Test
    @DisplayName("ACTIVE remains ACTIVE when quiescing")
    void activeRemainsActiveWhenQuiescing() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        time.tick(Duration.ofSeconds(6));
        assertEquals(
                CHECKING,
                stateMachine.submitTrigger(
                        new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(false, time.now()))));
        assertEquals(ACTIVE, stateMachine.submitTrigger(new SelfEventReachedConsensusTrigger(time.now())));
        time.tick(Duration.ofSeconds(15));
        // When quiescing, should remain ACTIVE despite time elapsed
        assertNull(stateMachine.submitTrigger(
                new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(true, time.now()))));
    }

    @Test
    @DisplayName("ACTIVE remains ACTIVE when insufficient time since quiescence command")
    void activeRemainsActiveWhenInsufficientTimeSinceQuiescenceCommand() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        time.tick(Duration.ofSeconds(6));
        assertEquals(
                CHECKING,
                stateMachine.submitTrigger(
                        new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(false, time.now()))));
        assertEquals(ACTIVE, stateMachine.submitTrigger(new SelfEventReachedConsensusTrigger(time.now())));
        final var before = time.now();
        time.tick(Duration.ofSeconds(5));
        // Should remain ACTIVE when not enough time has passed since quiescence command (5s < 10s delay)
        assertNull(stateMachine.submitTrigger(
                new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(false, before))));
    }

    @Test
    @DisplayName(
            "ACTIVE goes to CHECKING when sufficient time since quiescence command and an event reaching consensus")
    void activeMovesToCheckingWhenSufficientTimeSinceQuiescenceCommand() {
        assertEquals(
                REPLAYING_EVENTS,
                stateMachine.submitTrigger(new org.hiero.consensus.status.triggers.StartedReplayingEventsTrigger()));
        assertEquals(OBSERVING, stateMachine.submitTrigger(new DoneReplayingEventsTrigger(time.now())));
        time.tick(Duration.ofSeconds(6));
        assertEquals(
                CHECKING,
                stateMachine.submitTrigger(
                        new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(false, time.now()))));
        // MOVES instantly to ACTIVE since isQuiescing
        assertEquals(
                ACTIVE,
                stateMachine.submitTrigger(
                        new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(true, time.now()))));
        var before = time.now();
        time.tick(Duration.ofSeconds(4));
        // Should remain ACTIVE since not enough time has pass
        assertNull(stateMachine.submitTrigger(
                new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(true, before))));
        assertNull(stateMachine.submitTrigger(new SelfEventReachedConsensusTrigger(time.now())));
        time.tick(Duration.ofSeconds(5));
        // Should remain ACTIVE when not enough time has passed since quiescence command
        assertNull(stateMachine.submitTrigger(
                new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(false, time.now()))));
        before = time.now();
        time.tick(Duration.ofSeconds(11));
        // Should move to checking since its has happened enough time since the last event reached consensus and
        // quiescing
        assertEquals(
                CHECKING,
                stateMachine.submitTrigger(
                        new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(false, before))));
    }
}
