// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class IdempotentMetricsBinderTest {

    private MetricRegistry registry;

    @BeforeEach
    void setUp() {
        registry = MetricRegistry.builder("registry").build();
    }

    @Test
    void testBindOnlyCalledOnceSingleThread() {
        AtomicInteger bindCount = new AtomicInteger(0);

        IdempotentMetricsBinder binder = new IdempotentMetricsBinder() {
            @Override
            protected void bindMetricsNonIdempotent(@NonNull MetricRegistry registry) {
                bindCount.incrementAndGet();
            }
        };

        binder.bind(registry);
        binder.bind(registry);
        binder.bind(registry);

        assertThat(bindCount.get()).isEqualTo(1);
    }

    @Test
    void testIsMetricsBoundReturnsFalseInitially() {
        IdempotentMetricsBinder binder = new IdempotentMetricsBinder() {
            @Override
            protected void bindMetricsNonIdempotent(@NonNull MetricRegistry registry) {
                // no-op
            }
        };

        assertThat(binder.isMetricsBound()).isFalse();
    }

    @Test
    void testIsMetricsBoundReturnsTrueAfterBinding() {
        IdempotentMetricsBinder binder = new IdempotentMetricsBinder() {
            @Override
            protected void bindMetricsNonIdempotent(@NonNull MetricRegistry registry) {
                // no-op
            }
        };

        binder.bind(registry);

        assertThat(binder.isMetricsBound()).isTrue();
    }

    @Test
    void testNullRegistryThrowsException() {
        IdempotentMetricsBinder binder = new IdempotentMetricsBinder() {
            @Override
            protected void bindMetricsNonIdempotent(@NonNull MetricRegistry registry) {
                // no-op
            }
        };

        assertThatThrownBy(() -> binder.bind(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("metrics registry must not be null");
    }

    @Test
    void testRegistryPassedToBindMethod() {
        MetricRegistry[] capturedRegistry = new MetricRegistry[1];

        IdempotentMetricsBinder binder = new IdempotentMetricsBinder() {
            @Override
            protected void bindMetricsNonIdempotent(@NonNull MetricRegistry registry) {
                capturedRegistry[0] = registry;
            }
        };

        binder.bind(registry);

        assertThat(capturedRegistry[0]).isSameAs(registry);
    }

    @Test
    void testThreadSafetyMultipleConcurrentBinds() throws InterruptedException {
        AtomicInteger bindCount = new AtomicInteger(0);
        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        IdempotentMetricsBinder binder = new IdempotentMetricsBinder() {
            @Override
            protected void bindMetricsNonIdempotent(@NonNull MetricRegistry registry) {
                bindCount.incrementAndGet();
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    binder.bind(registry);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(bindCount.get()).isEqualTo(1);
        assertThat(binder.isMetricsBound()).isTrue();
    }
}
