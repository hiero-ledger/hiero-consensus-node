// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.consensus.flicker;

import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.ConsensusTraceLog;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.FlickerIntake;
import org.hiero.consensus.hashgraph.impl.test.fixtures.flicker.NamedEvent;
import org.hiero.consensus.model.roster.RosterWrapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Prints the whole trace log for {@link LadderGraph}. Asserts nothing — it is here to be read.
 * <p>
 * Run it with:
 * <pre>
 * ./gradlew :consensus-hashgraph-impl:test --tests '*TraceDumpTest*' --rerun-tasks -i
 * </pre>
 * The {@code --rerun-tasks} matters: without it Gradle reports the task up to date and prints nothing. The {@code -i}
 * is what lets {@code System.out} through.
 */
class TraceDumpTest {

    private static final long SEED = 20260925L;

    @Disabled
    @Test
    @DisplayName("print the LadderGraph trace")
    void printTrace() {
        final RosterWrapper roster = LadderGraph.roster();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final ConsensusTraceLog traceLog = new ConsensusTraceLog();
        final FlickerIntake intake = new FlickerIntake(configuration, roster, traceLog);

        for (final NamedEvent named : LadderGraph.build(roster)) {
            intake.add(named.name(), named.event());
        }

        System.out.println("--- LadderGraph trace: " + traceLog.changes().size() + " changes, "
                + intake.getConsensusRounds().size() + " round(s) decided ---");
        System.out.println(traceLog.render(null));
    }
}
