// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.concurrent;

import static org.hiero.consensus.concurrent.manager.AdHocThreadManager.getStaticThreadManager;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.concurrent.Callable;
import org.hiero.consensus.concurrent.test.fixtures.threading.ExceptionSuppressingParallelExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExceptionSuppressingParallelExecutorTests {

    @Test
    @DisplayName("Test exception suppressed")
    void testExceptionsSuppressed() {
        ExceptionSuppressingParallelExecutor executor =
                new ExceptionSuppressingParallelExecutor(getStaticThreadManager());
        executor.start();

        assertDoesNotThrow(
                () -> executor.doParallel(
                        (Callable<Void>) () -> {
                            throw new NullPointerException();
                        },
                        () -> {}),
                "Exceptions from task 1 should be suppressed.");

        assertDoesNotThrow(
                () -> executor.doParallel(() -> null, () -> {
                    throw new NullPointerException();
                }),
                "Exceptions from task 2 should be suppressed.");
    }
}
