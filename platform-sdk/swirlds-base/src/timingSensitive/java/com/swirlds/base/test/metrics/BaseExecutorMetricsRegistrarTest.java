// SPDX-License-Identifier: Apache-2.0
package com.swirlds.base.test.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.swirlds.base.internal.BaseExecutorFactory;
import com.swirlds.base.metrics.BaseExecutorMetricsRegistrar;
import com.swirlds.base.metrics.BaseExecutorMetricsRegistrar.Observer;
import com.swirlds.base.metrics.BaseExecutorMetricsRegistrar.TaskInfo;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class BaseExecutorMetricsRegistrarTest {

    private static final int TIMEOUT_MS = 2000;

    private final AtomicReference<TaskInfo> submitted = new AtomicReference<>();
    private final AtomicReference<TaskInfo> started = new AtomicReference<>();
    private final AtomicReference<TaskInfo> done = new AtomicReference<>();
    private final AtomicReference<TaskInfo> failed = new AtomicReference<>();
    private final AtomicReference<Duration> doneDuration = new AtomicReference<>();
    private final AtomicReference<Duration> failedDuration = new AtomicReference<>();

    private final CountDownLatch submittedLatch = new CountDownLatch(1);
    private final CountDownLatch startedLatch = new CountDownLatch(1);
    private final CountDownLatch doneLatch = new CountDownLatch(1);
    private final CountDownLatch failedLatch = new CountDownLatch(1);

    private final Observer observer = new Observer() {
        @Override
        public void onTaskSubmitted(TaskInfo taskInfo) {
            submitted.set(taskInfo);
            submittedLatch.countDown();
        }

        @Override
        public void onTaskStarted(TaskInfo taskInfo) {
            started.set(taskInfo);
            startedLatch.countDown();
        }

        @Override
        public void onTaskDone(TaskInfo taskInfo, Duration duration) {
            done.set(taskInfo);
            doneDuration.set(duration);
            doneLatch.countDown();
        }

        @Override
        public void onTaskFailed(TaskInfo taskInfo, Duration duration) {
            failed.set(taskInfo);
            failedDuration.set(duration);
            failedLatch.countDown();
        }
    };

    @BeforeEach
    void setUp() {
        BaseExecutorMetricsRegistrar.addObserver(observer);
    }

    @AfterEach
    void tearDown() {
        BaseExecutorMetricsRegistrar.removeObserver(observer);
    }

    @Test
    void testHappyPathTaskEvents() throws Exception {
        final BaseExecutorFactory factory = BaseExecutorFactory.getInstance();

        final CountDownLatch runLatch = new CountDownLatch(1);
        final Future<Void> future = factory.submit(() -> runLatch.countDown());

        assertThatNoException().isThrownBy(() -> runLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertThatNoException().isThrownBy(() -> future.get(1, TimeUnit.SECONDS));

        // wait for adapter events
        assertThatNoException().isThrownBy(() -> submittedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertThatNoException().isThrownBy(() -> startedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertThatNoException().isThrownBy(() -> doneLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        assertThat(submitted.get()).isNotNull();
        assertThat(started.get()).isNotNull();
        assertThat(done.get()).isNotNull();
        assertThat(doneDuration.get()).isNotNull();
    }

    @Test
    void testFailedTaskEvents() throws Exception {
        final BaseExecutorFactory factory = BaseExecutorFactory.getInstance();

        final Future<Void> future = factory.submit(() -> {
            throw new RuntimeException("expected");
        });

        try {
            future.get(1, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // expected
        }

        assertThatNoException().isThrownBy(() -> submittedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertThatNoException().isThrownBy(() -> startedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));
        assertThatNoException().isThrownBy(() -> failedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS));

        assertThat(submitted.get()).isNotNull();
        assertThat(started.get()).isNotNull();
        assertThat(failed.get()).isNotNull();
        assertThat(failedDuration.get()).isNotNull();
    }
}
