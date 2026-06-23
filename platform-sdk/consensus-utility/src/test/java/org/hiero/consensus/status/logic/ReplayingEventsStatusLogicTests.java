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
 * Unit tests for {@link ReplayingEventsStatusLogic}.
 */
class ReplayingEventsStatusLogicTests {
    private FakeTime time;
    private ReplayingEventsStatusLogic logic;

    @BeforeEach
    void setup() {
        time = new FakeTime();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        logic = new ReplayingEventsStatusLogic(configuration.getConfigData(PlatformStatusConfig.class));
    }

    @Test
    @DisplayName("Go to OBSERVING")
    void toObserving() {
        assertTransition(logic, new DoneReplayingEventsTrigger(time.now()), PlatformStatus.OBSERVING);
    }

    @Test
    @DisplayName("Go to CATASTROPHIC_FAILURE")
    void toCatastrophicFailure() {
        assertTransition(logic, new CatastrophicFailureTrigger(), PlatformStatus.CATASTROPHIC_FAILURE);
    }

    @Test
    @DisplayName("Wait in REPLAYING_EVENTS until freeze state is written if boundary is crossed")
    void freezeBoundaryCrossed() {
        assertNoTransition(logic, new FreezePeriodEnteredTrigger(6L), logic.getStatus());
        assertNoTransition(logic, new DoneReplayingEventsTrigger(time.now()), logic.getStatus());
        // if the state written to disk isn't the freeze state, we shouldn't transition
        assertNoTransition(logic, new StateWrittenToDiskTrigger(5, false), logic.getStatus());
        assertTransition(logic, new StateWrittenToDiskTrigger(6, true), PlatformStatus.FREEZE_COMPLETE);
    }

    @Test
    @DisplayName("Go to FREEZE_COMPLETE")
    void toFreezeComplete() {
        assertTransition(logic, new StateWrittenToDiskTrigger(0, true), PlatformStatus.FREEZE_COMPLETE);
    }

    @Test
    @DisplayName("Throw exception when receiving duplicate freeze round notification")
    void duplicateFreezeRound() {
        assertNoTransition(logic, new FreezePeriodEnteredTrigger(0), logic.getStatus());
        assertException(logic, new FreezePeriodEnteredTrigger(0), logic.getStatus());
    }

    @Test
    @DisplayName("Irrelevant triggers shouldn't cause transitions")
    void irrelevantTriggers() {
        assertNoTransition(
                logic,
                new TimeElapsedTrigger(time.now(), new TimeElapsedTrigger.QuiescingStatus(false, time.now())),
                logic.getStatus());
        assertNoTransition(
                logic,
                new TimeElapsedTrigger(
                        time.now(),
                        new TimeElapsedTrigger.QuiescingStatus(true, time.now().plus(5, ChronoUnit.SECONDS))),
                logic.getStatus());
        assertNoTransition(logic, new SelfEventReachedConsensusTrigger(time.now()), logic.getStatus());
        assertNoTransition(logic, new StateWrittenToDiskTrigger(0, false), logic.getStatus());
    }

    @Test
    @DisplayName("Unexpected triggers should cause exceptions")
    void unexpectedTriggers() {
        assertException(logic, new StartedReplayingEventsTrigger(), logic.getStatus());
        assertException(logic, new FallenBehindTrigger(), logic.getStatus());
        assertException(logic, new ReconnectCompleteTrigger(0), logic.getStatus());
    }
}
