// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.swirlds.common.test.fixtures.threading.ReplaceSyncPhaseParallelExecutor;
import com.swirlds.common.threading.pool.ParallelExecutionException;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ReplaceSyncPhaseParallelExecutorTests {

    private static Stream<Arguments> testParams() {
        return Stream.of(
                Arguments.of(1, 1),
                Arguments.of(1, 2),
                Arguments.of(2, 1),
                Arguments.of(2, 2),
                Arguments.of(3, 1),
                Arguments.of(3, 2));
    }

    @BeforeEach
    public void setup() {
        for (final PhaseTask phaseTask : PhaseTask.values()) {
            phaseTask.reset();
        }
    }

    @ParameterizedTest
    @MethodSource("testParams")
    void testReplacePhase(final int phaseNum, final int taskNum) throws ParallelExecutionException {
        final AtomicInteger replacementTask = new AtomicInteger(0);
        final ReplaceSyncPhaseParallelExecutor executor = new ReplaceSyncPhaseParallelExecutor(
                getStaticThreadManager(), phaseNum, taskNum, replacementTask::incrementAndGet);
        executor.start();

        executor.doParallel(
                toCallable(PhaseTask.PHASE1_TASK1.getTask()::incrementAndGet),
                PhaseTask.PHASE1_TASK2.getTask()::incrementAndGet);
        executor.doParallel(
                toCallable(PhaseTask.PHASE2_TASK1.getTask()::incrementAndGet),
                PhaseTask.PHASE2_TASK2.getTask()::incrementAndGet);
        executor.doParallel(
                toCallable(PhaseTask.PHASE3_TASK1.getTask()::incrementAndGet),
                PhaseTask.PHASE3_TASK2.getTask()::incrementAndGet);

        for (final PhaseTask phaseTask : PhaseTask.values()) {
            if (phaseTask.matches(phaseNum, taskNum)) {
                assertEquals(
                        0,
                        phaseTask.getTask().get(),
                        format(
                                "Phase %s, Task %s should have been replaced with the replacement task.",
                                phaseNum, taskNum));
            } else {
                assertEquals(
                        1,
                        phaseTask.getTask().get(),
                        format(
                                "Phase %s, Task %s should not have been replaced with the replacement task.",
                                phaseNum, taskNum));
            }
        }

        assertEquals(1, replacementTask.get(), "The replacement task should have run.");
    }

    private enum PhaseTask {
        PHASE1_TASK1(1, 1),
        PHASE1_TASK2(1, 2),
        PHASE2_TASK1(2, 1),
        PHASE2_TASK2(2, 2),
        PHASE3_TASK1(3, 1),
        PHASE3_TASK2(3, 2);

        private final int phaseNum;
        private final int taskNum;
        private final AtomicInteger task;

        PhaseTask(final int phaseNum, final int taskNum) {
            this.phaseNum = phaseNum;
            this.taskNum = taskNum;
            task = new AtomicInteger(0);
        }

        public boolean matches(final int phaseNum, final int taskNum) {
            return this.phaseNum == phaseNum && this.taskNum == taskNum;
        }

        public AtomicInteger getTask() {
            return task;
        }

        public void reset() {
            task.set(0);
        }
    }

    private static Callable<Void> toCallable(final Supplier<?> supplier) {
        return () -> {
            supplier.get();
            return null;
        };
    }
}
