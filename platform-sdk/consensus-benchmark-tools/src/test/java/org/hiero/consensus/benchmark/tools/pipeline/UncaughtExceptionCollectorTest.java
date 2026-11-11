// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.benchmark.tools.pipeline;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class UncaughtExceptionCollectorTest {

    @Test
    void nothingIsThrownWithoutAnException() {
        final UncaughtExceptionCollector collector = new UncaughtExceptionCollector();

        assertThatCode(collector::throwIfAny).doesNotThrowAnyException();
    }

    @Test
    void theFirstExceptionIsTheCause() throws InterruptedException {
        final UncaughtExceptionCollector collector = new UncaughtExceptionCollector();
        final RuntimeException first = new RuntimeException("first");
        final Thread thread = Thread.ofPlatform().name("pipeline-1").unstarted(() -> {
            throw first;
        });
        thread.setUncaughtExceptionHandler(collector);
        thread.start();
        thread.join();
        collector.uncaughtException(Thread.currentThread(), new RuntimeException("second"));
        collector.uncaughtException(Thread.currentThread(), new RuntimeException("third"));

        assertThatThrownBy(collector::throwIfAny)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The pipeline thread pipeline-1 threw, and 2 more exceptions followed")
                .hasCause(first);
    }
}
