// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import static org.hiero.consensus.status.logic.StatusLogicTestUtils.assertException;
import static org.hiero.consensus.status.logic.StatusLogicTestUtils.assertNoTransition;
import static org.hiero.consensus.status.logic.StatusLogicTestUtils.assertTransition;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.time.Duration;
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
 * Tests for {@link CheckingStatusLogic}.
 */
class CheckingStatusLogicTests {
    private FakeTime time;
    private CheckingStatusLogic logic;

    @BeforeEach
    void setup() {
        time = new FakeTime();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        logic = new CheckingStatusLogic(configuration.getConfigData(PlatformStatusConfig.class));
    }

    @Test
    @DisplayName("Go to FREEZING")
    void toFreezing() {
        assertTransition(logic, new FreezePeriodEnteredTrigger(0), PlatformStatus.FREEZING);
    }

    @Test
    @DisplayName("Go to BEHIND")
    void toBehind() {
        assertTransition(logic, new FallenBehindTrigger(), PlatformStatus.BEHIND);
    }

    @Test
    @DisplayName("Go to ACTIVE")
    void toActive() {
        assertTransition(logic, new SelfEventReachedConsensusTrigger(time.now()), PlatformStatus.ACTIVE);
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
        assertException(logic, new StartedReplayingEventsTrigger(), logic.getStatus());
        assertException(logic, new DoneReplayingEventsTrigger(time.now()), logic.getStatus());
        assertException(logic, new ReconnectCompleteTrigger(0), logic.getStatus());
    }

    @Test
    @DisplayName("Go to ACTIVE when quiescing")
    void toActiveWhenQuiescing() {
        // Should transition to ACTIVE when quiescing
        assertTransition(
                logic,
                new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(true, time.now())),
                PlatformStatus.ACTIVE);
    }

    @Test
    @DisplayName("remain in CHECKING when not quiescing")
    void remainInCheckingNotQuiescing() {
        assertTransition(
                logic,
                new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(false, time.now())),
                PlatformStatus.CHECKING);
        time.tick(Duration.ofSeconds(6));
        // Should transition to ACTIVE when quiescing
        assertTransition(
                logic,
                new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(false, time.now())),
                PlatformStatus.CHECKING);
    }
}
