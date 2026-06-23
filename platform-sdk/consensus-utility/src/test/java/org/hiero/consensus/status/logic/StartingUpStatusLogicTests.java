// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.status.logic;

import static org.hiero.consensus.status.logic.StatusLogicTestUtils.assertException;
import static org.hiero.consensus.status.logic.StatusLogicTestUtils.assertNoTransition;
import static org.hiero.consensus.status.logic.StatusLogicTestUtils.assertTransition;

import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
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
 * Unit tests for {@link StartingUpStatusLogic}.
 */
class StartingUpStatusLogicTests {
    private FakeTime time;
    private StartingUpStatusLogic logic;

    @BeforeEach
    void setup() {
        time = new FakeTime();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        logic = new StartingUpStatusLogic(configuration.getConfigData(PlatformStatusConfig.class));
    }

    @Test
    @DisplayName("Go to REPLAYING_EVENTS")
    void toReplayingEvents() {
        assertTransition(logic, new StartedReplayingEventsTrigger(), PlatformStatus.REPLAYING_EVENTS);
    }

    @Test
    @DisplayName("Go to CATASTROPHIC_FAILURE")
    void toCatastrophicFailure() {
        assertTransition(logic, new CatastrophicFailureTrigger(), PlatformStatus.CATASTROPHIC_FAILURE);
    }

    @Test
    @DisplayName("Irrelevant triggers shouldn't cause transitions")
    void irrelevantTriggers() {
        assertNoTransition(
                logic,
                new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(false, time.now())),
                logic.getStatus());
    }

    @Test
    @DisplayName("Unexpected triggers should cause exceptions")
    void unexpectedTriggers() {
        assertException(logic, new DoneReplayingEventsTrigger(time.now()), logic.getStatus());
        assertException(logic, new SelfEventReachedConsensusTrigger(time.now()), logic.getStatus());
        assertException(logic, new FreezePeriodEnteredTrigger(0), logic.getStatus());
        assertException(logic, new FallenBehindTrigger(), logic.getStatus());
        assertException(logic, new ReconnectCompleteTrigger(0), logic.getStatus());
        assertException(logic, new StateWrittenToDiskTrigger(0, false), logic.getStatus());
        assertException(logic, new StateWrittenToDiskTrigger(0, true), logic.getStatus());
    }
}
