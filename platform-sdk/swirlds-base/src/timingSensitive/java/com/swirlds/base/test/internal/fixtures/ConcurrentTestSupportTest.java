// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.internal.fixtures;

import static java.util.concurrent.CompletableFuture.runAsync;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.swirlds.base.test.fixtures.concurrent.internal.ConcurrentTestSupport;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class ConcurrentTestSupportTest {

    @Test
    void testATaskThatRunsShort() {
        // given
        try (ConcurrentTestSupport concurrentTestSupport = new ConcurrentTestSupport(Duration.ofSeconds(1))) {
            final Runnable runnable = () -> sleep(10);

            // then
            assertThatNoException().isThrownBy(() -> concurrentTestSupport.executeAndWait(runnable));
        }
    }

    @Test
    void testMultipleTasksThatRunsShort() {
        // given
        try (ConcurrentTestSupport concurrentTestSupport = new ConcurrentTestSupport(Duration.ofSeconds(1))) {
            final List<Runnable> runnables = IntStream.range(10, 20)
                    .mapToObj(i -> (Runnable) () -> sleep(i))
                    .toList();

            // then
            assertThatNoException().isThrownBy(() -> concurrentTestSupport.executeAndWait(runnables));
        }
    }

    @Test
    void testATaskThatRunsTooLong() {
        // given
        try (ConcurrentTestSupport concurrentTestSupport = new ConcurrentTestSupport(Duration.ofMillis(500))) {
            final Runnable runnable = () -> sleep(1_010);

            // then
            assertThatThrownBy(() -> concurrentTestSupport.executeAndWait(runnable))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void testMultipleTasksThatRunsTooLong() {
        // given
        try (ConcurrentTestSupport concurrentTestSupport = new ConcurrentTestSupport(Duration.ofSeconds(1)); ) {
            final List<Runnable> runnables = IntStream.range(0, 10)
                    .mapToObj(i -> (Runnable) () -> sleep(2000))
                    .toList();

            // then
            assertThatThrownBy(() -> concurrentTestSupport.executeAndWait(runnables))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void testMultipleCallsInOneConcurrentTestSupport() {
        // given
        try (ConcurrentTestSupport concurrentTestSupport = new ConcurrentTestSupport(Duration.ofSeconds(1)); ) {
            final Runnable shortRunningTask = () -> sleep(10);
            final Runnable longRunningTask = () -> sleep(2_000);
            final ExecutorService executor = Executors.newFixedThreadPool(2);

            // when
            runAllAndWait(
                    runAsync(
                            () -> assertThatThrownBy(() -> concurrentTestSupport.executeAndWait(longRunningTask))
                                    .isInstanceOf(RuntimeException.class)
                                    .hasCauseInstanceOf(TimeoutException.class),
                            executor),
                    runAsync(
                            () -> assertThatNoException()
                                    .isThrownBy(() -> concurrentTestSupport.executeAndWait(shortRunningTask)),
                            executor));
        }
    }

    @Test
    void testMultipleCallsInMultipleConcurrentTestSupport() {
        // given
        try (ConcurrentTestSupport concurrentTestSupport = new ConcurrentTestSupport(Duration.ofSeconds(1));
                ConcurrentTestSupport concurrentTestSupport2 = new ConcurrentTestSupport(Duration.ofSeconds(1)); ) {
            final Runnable shortRunningTask = () -> sleep(100);
            final Runnable longRunningTask = () -> sleep(2_000);
            final ExecutorService executor = Executors.newFixedThreadPool(4);

            // then
            runAllAndWait(
                    runAsync(
                            () -> assertThatNoException()
                                    .isThrownBy(() -> concurrentTestSupport.executeAndWait(shortRunningTask)),
                            executor),
                    runAsync(
                            () -> assertThatNoException()
                                    .isThrownBy(() -> concurrentTestSupport2.executeAndWait(shortRunningTask)),
                            executor),
                    runAsync(
                            () -> assertThatThrownBy(() -> concurrentTestSupport.executeAndWait(longRunningTask))
                                    .isInstanceOf(RuntimeException.class)
                                    .hasCauseInstanceOf(TimeoutException.class),
                            executor),
                    runAsync(
                            () -> assertThatThrownBy(() -> concurrentTestSupport2.executeAndWait(longRunningTask))
                                    .isInstanceOf(RuntimeException.class)
                                    .hasCauseInstanceOf(TimeoutException.class),
                            executor));
        }
    }

    @Test
    void testMultipleCallable() {
        // given
        try (ConcurrentTestSupport concurrentTestSupport = new ConcurrentTestSupport(Duration.ofSeconds(1))) {
            final Callable<Object> shortCallableTask = () -> getAfter(100, new Object());
            final Callable<Object> longCallableTask = () -> getAfter(2_000, new Object());

            assertThatNoException().isThrownBy(() -> concurrentTestSupport.submitAndWait(shortCallableTask));

            assertThatThrownBy(() -> concurrentTestSupport.submitAndWait(longCallableTask))
                    .isInstanceOf(RuntimeException.class)
                    .hasCauseInstanceOf(TimeoutException.class);
        }
    }

    @Test
    void testShortCallable() {
        // given
        try (ConcurrentTestSupport concurrentTestSupport = new ConcurrentTestSupport(Duration.ofSeconds(1))) {
            Object value = new Object();
            final Callable<Object> shortCallableTask = () -> getAfter(100, value);

            // then
            assertThat(concurrentTestSupport.submitAndWait(shortCallableTask))
                    .contains(value)
                    .hasSize(1);
        }
    }

    private static void runAllAndWait(CompletableFuture<?>... futures) {
        CompletableFuture.allOf(futures).join();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            System.err.println("interrupted");
        }
    }

    private static <V> V getAfter(long ms, V value) {
        sleep(ms);
        return value;
    }
}
