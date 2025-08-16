// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading;

import static com.swirlds.common.threading.manager.AdHocThreadManager.getStaticThreadManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.common.threading.pool.CachedPoolParallelExecutor;
import com.swirlds.common.threading.pool.ParallelExecutionException;
import com.swirlds.common.threading.pool.ParallelExecutor;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import org.hiero.base.concurrent.ThrowingRunnable;
import org.hiero.base.utility.test.fixtures.tags.TestComponentTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@DisplayName("Parallel Executor Tests")
class CachedPoolParallelExecutorTest {

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Simple 2 parallel task test")
    void simpleTasks() throws Exception {
        final ParallelExecutor executor = new CachedPoolParallelExecutor(getStaticThreadManager(), "a name");
        executor.start();
        // create 2 latches where both threads need to do the countdown on one and wait for the other
        // these 2 operations need to happen in parallel
        final CountDownLatch latch1 = new CountDownLatch(1);
        final CountDownLatch latch2 = new CountDownLatch(1);
        final long expectedReturn = new Random().nextLong();
        final Callable<Long> task1 = () -> {
            latch1.countDown();
            latch2.await();
            return expectedReturn;
        };
        final ThrowingRunnable task2 = () -> {
            latch2.countDown();
            latch1.await();
        };
        final Long actualReturn = executor.doParallel(task1, task2);

        assertEquals(0, latch1.getCount(), "thread 1 should have done a countdown");
        assertEquals(0, latch2.getCount(), "thread 2 should have done a countdown");
        assertEquals(expectedReturn, actualReturn, "doParallel did not return the correct value");
    }

    @Test
    @Tag(TestComponentTags.THREADING)
    @DisplayName("Exception test")
    void testException() {
        final ParallelExecutor executor = new CachedPoolParallelExecutor(getStaticThreadManager(), "a name");
        executor.start();
        final Exception exception1 = new Exception("exception 1");
        final Exception exception2 = new Exception("exception 2");
        final AssertionError error1 = new AssertionError("error 1");

        final ThrowingRunnable task1 = () -> {
            throw exception1;
        };
        final ThrowingRunnable task2 = () -> {
            throw exception2;
        };
        final ThrowingRunnable task3 = () -> {
            throw error1;
        };

        final ThrowingRunnable noEx = () -> {};

        // check if exceptions get thrown as intended
        ParallelExecutionException ex;

        ex = assertThrows(ParallelExecutionException.class, () -> executor.doParallel(task1, task2));
        assertThat(ex).hasCause(exception1);
        assertThat(ex.getSuppressed()).hasSize(1);
        assertThat(ex.getSuppressed()[0]).hasCause(exception2);

        ex = assertThrows(ParallelExecutionException.class, () -> executor.doParallel(task1, noEx));
        assertThat(ex).hasCause(exception1);
        assertThat(ex.getSuppressed()).isEmpty();

        ex = assertThrows(ParallelExecutionException.class, () -> executor.doParallel(noEx, task2));
        assertThat(ex.getCause()).hasCause(exception2);
        assertThat(ex.getSuppressed()).isEmpty();

        ex = assertThrows(ParallelExecutionException.class, () -> executor.doParallel(task3, noEx));
        assertThat(ex).hasCause(error1);
        assertThat(ex.getSuppressed()).isEmpty();

        ex = assertThrows(ParallelExecutionException.class, () -> executor.doParallel(noEx, task3));
        assertThat(ex.getCause()).hasCause(error1);
        assertThat(ex.getSuppressed()).isEmpty();
    }
}
