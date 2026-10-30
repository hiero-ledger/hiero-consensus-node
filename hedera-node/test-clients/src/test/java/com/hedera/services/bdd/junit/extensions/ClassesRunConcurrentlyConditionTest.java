// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.extensions;

import static com.hedera.services.bdd.junit.extensions.ClassesRunConcurrentlyCondition.classesRunConcurrently;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ClassesRunConcurrentlyCondition}.
 */
class ClassesRunConcurrentlyConditionTest {
    private static final String ENABLED = "junit.jupiter.execution.parallel.enabled";
    private static final String MODE = "junit.jupiter.execution.parallel.mode.default";
    private static final String CLASSES_MODE = "junit.jupiter.execution.parallel.mode.classes.default";

    private static boolean concurrent(final Map<String, String> parameters) {
        return classesRunConcurrently(key -> Optional.ofNullable(parameters.get(key)));
    }

    @Test
    @DisplayName("Classes run concurrently only with parallel execution on and a concurrent classes mode")
    void concurrentClasses() {
        assertTrue(concurrent(Map.of(ENABLED, "true", CLASSES_MODE, "concurrent")));
        assertTrue(concurrent(Map.of(ENABLED, "true", MODE, "concurrent")), "classes inherit the default mode");
    }

    @Test
    @DisplayName("Classes run one at a time without parallel execution, or with a same_thread classes mode")
    void sequentialClasses() {
        assertFalse(concurrent(Map.of()), "parallel execution is off by default");
        assertFalse(concurrent(Map.of(CLASSES_MODE, "concurrent")), "parallel execution is off");
        assertFalse(concurrent(Map.of(ENABLED, "true")), "the default mode is same_thread");
        assertFalse(
                concurrent(Map.of(ENABLED, "true", MODE, "concurrent", CLASSES_MODE, "same_thread")),
                "as testSubprocess runs: methods concurrent, classes one at a time");
    }
}
