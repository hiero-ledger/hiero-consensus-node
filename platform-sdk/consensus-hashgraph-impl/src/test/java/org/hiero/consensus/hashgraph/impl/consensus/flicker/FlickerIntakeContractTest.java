// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import java.util.List;
import java.util.Random;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerIntake;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory;
import org.hiero.consensus.test.fixtures.WeightGenerators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The authoring contract {@link FlickerIntake} enforces on a graph.
 * <p>
 * Production copes with events that are not ready for consensus: the future event buffer holds one whose birth round
 * is ahead of the pending consensus round and releases it later, and both the future event buffer and the orphan
 * buffer silently drop ancient events. The harness reproduces none of that. It rejects the event instead, because a
 * hand-derived expectation cannot survive its subject being deferred to a moment the author did not choose, and
 * because the alternative is a hand-maintained copy of production machinery that would drift.
 * <p>
 * These tests exist so the rejection is a guarantee rather than an implementation detail - a fixture author should get
 * a diagnosis, not a wrong answer.
 */
class FlickerIntakeContractTest {

    private static final long SEED = 20260925L;

    @Test
    @DisplayName("a future event is rejected rather than buffered")
    void futureEventIsRejected() {
        final RosterWrapper roster = RosterWrapperFactory.randomRoster(new Random(SEED), 4, WeightGenerators.BALANCED);
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final FlickerIntake intake = new FlickerIntake(configuration, roster);

        // At genesis the pending consensus round is 1, so a birth round of 2 is in the future. Production would hold
        // this event in the FutureEventBuffer and feed it to consensus at some later add; the harness refuses it.
        final PlatformEvent futureEvent = new TestingEventBuilder(new Random(SEED))
                .setCreatorId(roster.nodeIds().getFirst())
                .setBirthRound(2)
                .setTransactionBytes(List.of())
                .build();

        // Asserting on both numbers, not just the wording: it is what shows the rejection came from production's own
        // getMaximumReleasableRound rather than from some unrelated throw that happens to mention the right word.
        assertThatThrownBy(() -> intake.add("future", futureEvent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is a future event")
                .hasMessageContaining("birth round 2")
                .hasMessageContaining("pending consensus round 1");
    }
}
