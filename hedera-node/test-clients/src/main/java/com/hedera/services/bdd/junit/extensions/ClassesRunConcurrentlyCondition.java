// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Disables a multi-network validation test unless test classes run concurrently.
 *
 * <p>A validation test must run alongside the network groups it validates: a group waiting for validation keeps its
 * nodes, so in a run that executes classes one at a time, the groups after it would wait for nodes that only the
 * validation test, which runs after them, could free.
 */
public final class ClassesRunConcurrentlyCondition implements ExecutionCondition {
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(@NonNull final ExtensionContext context) {
        return classesRunConcurrently(context::getConfigurationParameter)
                ? ConditionEvaluationResult.enabled("Test classes run concurrently")
                : ConditionEvaluationResult.disabled("Needs test classes to run concurrently"
                        + " (junit.jupiter.execution.parallel.mode.classes.default=concurrent)");
    }

    /**
     * Returns whether JUnit runs test classes concurrently under the given configuration parameters.
     *
     * @param parameters looks up a JUnit configuration parameter by key
     * @return whether parallel execution is on and classes run concurrently
     */
    public static boolean classesRunConcurrently(@NonNull final Function<String, Optional<String>> parameters) {
        final boolean parallel = parameters
                .apply("junit.jupiter.execution.parallel.enabled")
                .map(value -> Boolean.parseBoolean(value.trim()))
                .orElse(false);
        // Classes default to the mode of everything else, which defaults to same_thread
        final var classesMode = parameters
                .apply("junit.jupiter.execution.parallel.mode.classes.default")
                .or(() -> parameters.apply("junit.jupiter.execution.parallel.mode.default"))
                .orElse("same_thread");
        return parallel && "concurrent".equalsIgnoreCase(classesMode.trim());
    }
}
