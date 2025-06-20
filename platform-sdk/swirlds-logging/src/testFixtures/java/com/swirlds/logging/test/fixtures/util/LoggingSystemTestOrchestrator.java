// SPDX-License-Identifier: Apache-2.0
package com.swirlds.logging.test.fixtures.util;

import com.swirlds.logging.api.internal.LoggingSystem;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class helps the test to:
 *  <ol>
 * <li>Store all {@code LoggingTestScenario}
 * <li>reload the configuration
 * <li>verify the scenario when the configuration is loaded
 *  <ol>
 */
public final class LoggingSystemTestOrchestrator {
    private final List<LoggingTestScenario> scenarios;

    /**
     * Runs the different {@code testScenarios} in random order up to {@code durationLimit}. All scenarios are run at
     * least once.
     */
    public static void runScenarios(
            final LoggingSystem loggingSystem, Duration durationLimit, LoggingTestScenario... loggingTestScenarios) {

        LoggingSystemTestOrchestrator orchestrator = new LoggingSystemTestOrchestrator(loggingTestScenarios);

        final List<Integer> list = IntStream.rangeClosed(0, loggingTestScenarios.length - 1)
                .boxed()
                .collect(Collectors.toList());
        Collections.shuffle(list);

        for (Integer index : list) {
            orchestrator.testScenario(index, loggingSystem);
        }
        if (durationLimit != null && !durationLimit.isZero()) {
            final long startTime = System.currentTimeMillis();
            long availableTime = durationLimit.toMillis() - (System.currentTimeMillis() - startTime);
            final Random random = new Random(System.currentTimeMillis());
            while (availableTime > 0) {
                orchestrator.testScenario(list.get(random.nextInt(list.size())), loggingSystem);
                availableTime -= (System.currentTimeMillis() - startTime);
            }
        } else {
            for (int i = 0; i < loggingTestScenarios.length; i++) {
                orchestrator.testScenario(list.get(i), loggingSystem);
            }
        }
    }

    public static void runScenarios(final LoggingSystem loggingSystem, LoggingTestScenario... loggingTestScenarios) {
        runScenarios(loggingSystem, null, loggingTestScenarios);
    }

    private LoggingSystemTestOrchestrator(LoggingTestScenario... loggingTestScenarios) {
        this.scenarios = List.of(loggingTestScenarios);
    }

    /**
     * Performs the verification of scenario given by its index on the list
     */
    private void testScenario(int scenario, LoggingSystem system) {
        final LoggingTestScenario loggingTestScenario = this.scenarios.get(scenario);
        // Reload Configuration for desired scenario
        system.update(loggingTestScenario.configuration());
        // Performs the check
        loggingTestScenario.verifyAssertionRules(system);
    }
}
