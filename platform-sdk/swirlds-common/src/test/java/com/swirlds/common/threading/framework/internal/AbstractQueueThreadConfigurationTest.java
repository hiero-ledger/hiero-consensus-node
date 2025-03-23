// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework.internal;

import static com.swirlds.metrics.api.Metrics.INTERNAL_CATEGORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.swirlds.common.metrics.PlatformMetricsFactory;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.common.threading.framework.QueueThread;
import com.swirlds.common.threading.framework.StoppableThread;
import com.swirlds.common.threading.framework.config.QueueThreadMetricsConfiguration;
import com.swirlds.common.threading.manager.ThreadManager;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.IntegerAccumulator;
import com.swirlds.metrics.api.Metrics;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.utility.interrupt.InterruptableConsumer;
import org.hiero.consensus.model.utility.interrupt.InterruptableRunnable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AbstractQueueThreadConfigurationTest {

    /**
     * Dummy QueueThreadConfiguration for testing
     */
    static class DummyQueueThreadConfiguration<T>
            extends AbstractQueueThreadConfiguration<DummyQueueThreadConfiguration<T>, T> {

        protected DummyQueueThreadConfiguration(final ThreadManager threadManager) {
            super(threadManager);
        }

        protected DummyQueueThreadConfiguration(
                final AbstractQueueThreadConfiguration<DummyQueueThreadConfiguration<T>, T> that) {
            super(that);
        }

        @Override
        public DummyQueueThreadConfiguration<T> copy() {
            return new DummyQueueThreadConfiguration<>(this);
        }
    }

    static final NodeId NODE_ID = NodeId.of(1L);
    static final String THREAD_POOL_NAME = "myThreadPool";
    static final String THREAD_NAME = "myThread";
    static final int MAX_BUFFER_SIZE = 50;
    static final int CAPACITY = 10;
    static final String MAX_SIZE_METRIC_NAME = THREAD_NAME + MeasuredBlockingQueue.QUEUE_MAX_SIZE_SUFFIX;
    static final String MIN_SIZE_METRIC_NAME = THREAD_NAME + MeasuredBlockingQueue.QUEUE_MIN_SIZE_SUFFIX;

    private Metrics metrics;

    @BeforeEach
    void setUp() {
        final MetricKeyRegistry registry = new MetricKeyRegistry();
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final PlatformMetricsFactory factory = new PlatformMetricsFactoryImpl(metricsConfig);
        metrics = new DefaultPlatformMetrics(null, registry, executor, factory, metricsConfig);
    }

    @Test
    @DisplayName("Testing constructor with thread manager and metrics")
    void constructorWithThreadManagerAndMetrics() {
        // given
        final ThreadManager threadManager = mock(ThreadManager.class);
        final InterruptableConsumer<String> handler = mock(InterruptableConsumer.class);
        final InterruptableRunnable waitForItemRunnable = mock(InterruptableRunnable.class);

        // when
        final DummyQueueThreadConfiguration<String> configuration = new DummyQueueThreadConfiguration<String>(
                        threadManager)
                .setNodeId(NODE_ID)
                .setComponent(THREAD_POOL_NAME)
                .setThreadName(THREAD_NAME)
                .setMaxBufferSize(MAX_BUFFER_SIZE)
                .setCapacity(CAPACITY)
                .setHandler(handler);

        // then
        assertThat(configuration.getNodeId()).isEqualTo(NODE_ID);
        assertThat(configuration.getComponent()).isEqualTo(THREAD_POOL_NAME);
        assertThat(configuration.getThreadName()).isEqualTo(THREAD_NAME);
        assertThat(configuration.getMaxBufferSize()).isEqualTo(MAX_BUFFER_SIZE);
        assertThat(configuration.getCapacity()).isEqualTo(CAPACITY);
        assertThat(configuration.getHandler()).isEqualTo(handler);
        assertThat(configuration.getQueue()).isNull();
    }

    @Test
    @DisplayName("Testing constructor with other configuration")
    void constructorWithOtherConfiguration() {
        // given
        final ThreadManager threadManager = mock(ThreadManager.class);
        final InterruptableConsumer<String> handler = mock(InterruptableConsumer.class);
        final InterruptableRunnable waitForItemRunnable = mock(InterruptableRunnable.class);
        final DummyQueueThreadConfiguration<String> configuration = new DummyQueueThreadConfiguration<String>(
                        threadManager)
                .setNodeId(NODE_ID)
                .setComponent(THREAD_POOL_NAME)
                .setThreadName(THREAD_NAME)
                .setMaxBufferSize(MAX_BUFFER_SIZE)
                .setCapacity(CAPACITY)
                .setHandler(handler);

        // when
        final DummyQueueThreadConfiguration<String> copied = new DummyQueueThreadConfiguration<>(configuration);

        // then
        assertThat(configuration.getNodeId()).isEqualTo(copied.getNodeId());
        assertThat(configuration.getComponent()).isEqualTo(copied.getComponent());
        assertThat(configuration.getThreadName()).isEqualTo(copied.getThreadName());
        assertThat(configuration.getMaxBufferSize()).isEqualTo(copied.getMaxBufferSize());
        assertThat(configuration.getCapacity()).isEqualTo(copied.getCapacity());
        assertThat(configuration.getHandler()).isEqualTo(copied.getHandler());
        assertThat(configuration.getQueue()).isEqualTo(copied.getQueue());
    }

    @Test
    @DisplayName("Testing build with start = false")
    void buildShouldCreateQueueThreadThatDoesNotStart() {
        // given
        final ThreadManager threadManager = mock(ThreadManager.class);
        final InterruptableConsumer<String> handler = mock(InterruptableConsumer.class);
        final InterruptableRunnable waitForItemRunnable = mock(InterruptableRunnable.class);

        // when
        final DummyQueueThreadConfiguration<String> configuration = new DummyQueueThreadConfiguration<String>(
                        threadManager)
                .setNodeId(NODE_ID)
                .setComponent(THREAD_POOL_NAME)
                .setThreadName(THREAD_NAME)
                .setMaxBufferSize(MAX_BUFFER_SIZE)
                .setCapacity(CAPACITY)
                .setHandler(handler);
        final QueueThread<String> queueThread = configuration.buildQueueThread(false);

        // then
        assertThat(configuration.getQueue()).isInstanceOf(LinkedBlockingQueue.class);
        assertThat(queueThread.getName()).isEqualTo(THREAD_NAME);
        assertThat(queueThread.getStatus()).isEqualTo(StoppableThread.Status.NOT_STARTED);
    }

    @Test
    @DisplayName("Testing build with start = true")
    void buildWithStartShouldCreateQueueThreadThatStarts() {
        // given
        final ThreadManager threadManager = mock(ThreadManager.class);
        final InterruptableConsumer<String> handler = mock(InterruptableConsumer.class);
        final InterruptableRunnable waitForItemRunnable = mock(InterruptableRunnable.class);

        // when
        when(threadManager.createThread(any(ThreadGroup.class), any(Runnable.class)))
                .thenReturn(new Thread());
        final DummyQueueThreadConfiguration<String> configuration = new DummyQueueThreadConfiguration<String>(
                        threadManager)
                .setNodeId(NODE_ID)
                .setComponent(THREAD_POOL_NAME)
                .setThreadName(THREAD_NAME)
                .setMaxBufferSize(MAX_BUFFER_SIZE)
                .setCapacity(CAPACITY)
                .setHandler(handler);
        final QueueThread<String> queueThread = configuration.buildQueueThread(true);

        // then
        assertThat(configuration.getQueue()).isInstanceOf(LinkedBlockingQueue.class);
        assertThat(queueThread.getName()).isEqualTo(THREAD_NAME);
        assertThat(queueThread.getStatus()).isEqualTo(StoppableThread.Status.ALIVE);
    }

    @Test
    @DisplayName("Testing build with external queue")
    void buildShouldCreateQueueThreadWithExternalQueue() {
        // given
        final ThreadManager threadManager = mock(ThreadManager.class);
        final InterruptableConsumer<String> handler = mock(InterruptableConsumer.class);
        final InterruptableRunnable waitForItemRunnable = mock(InterruptableRunnable.class);
        final BlockingQueue<String> queue = new PriorityBlockingQueue<>(List.of("A", "B", "C"));

        // when
        final DummyQueueThreadConfiguration<String> configuration = new DummyQueueThreadConfiguration<String>(
                        threadManager)
                .setNodeId(NODE_ID)
                .setComponent(THREAD_POOL_NAME)
                .setThreadName(THREAD_NAME)
                .setMaxBufferSize(MAX_BUFFER_SIZE)
                .setCapacity(CAPACITY)
                .setHandler(handler)
                .setQueue(queue);
        final QueueThread<String> queueThread = configuration.buildQueueThread(false);

        // then
        assertThat(configuration.getQueue()).isInstanceOf(PriorityBlockingQueue.class);
        assertThat(queueThread.getName()).isEqualTo(THREAD_NAME);
        assertThat(queueThread).hasSize(3).containsExactly("A", "B", "C");

        // when
        final IntegerAccumulator maxSizeMetric =
                (IntegerAccumulator) metrics.getMetric(INTERNAL_CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric =
                (IntegerAccumulator) metrics.getMetric(INTERNAL_CATEGORY, MIN_SIZE_METRIC_NAME);

        // then
        assertThat(maxSizeMetric).isNull();
        assertThat(minSizeMetric).isNull();
    }

    @Test
    @DisplayName("Testing build with metric enabled")
    void buildShouldCreateQueueThreadThatCanBeMonitoredWithMetrics() {
        // given
        final ThreadManager threadManager = mock(ThreadManager.class);
        final InterruptableConsumer<String> handler = mock(InterruptableConsumer.class);
        final InterruptableRunnable waitForItemRunnable = mock(InterruptableRunnable.class);
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("A", "B", "C"));

        // when
        final DummyQueueThreadConfiguration<String> configuration = new DummyQueueThreadConfiguration<String>(
                        threadManager)
                .setNodeId(NODE_ID)
                .setComponent(THREAD_POOL_NAME)
                .setThreadName(THREAD_NAME)
                .setMaxBufferSize(MAX_BUFFER_SIZE)
                .setCapacity(CAPACITY)
                .setHandler(handler)
                .setQueue(queue)
                .setMetricsConfiguration(new QueueThreadMetricsConfiguration(metrics)
                        .enableMaxSizeMetric()
                        .enableMinSizeMetric());
        final QueueThread<String> queueThread = configuration.buildQueueThread(false);

        // then
        assertThat(queueThread).hasSize(3);
        assertThat(configuration.getQueue()).isInstanceOf(MeasuredBlockingQueue.class);

        // when
        final IntegerAccumulator maxSizeMetric =
                (IntegerAccumulator) metrics.getMetric(INTERNAL_CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric =
                (IntegerAccumulator) metrics.getMetric(INTERNAL_CATEGORY, MIN_SIZE_METRIC_NAME);

        // then
        assertThat(maxSizeMetric).isNotNull();
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric).isNotNull();
        assertThat(minSizeMetric.get()).isEqualTo(3);

        // when
        queueThread.add("D");
        queueThread.poll();
        queueThread.poll();
        queueThread.poll();
        queueThread.add("E");

        // then
        assertThat(queueThread).hasSize(2);
        assertThat(maxSizeMetric.get()).isEqualTo(4);
        assertThat(minSizeMetric.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Testing build with metric enabled with null metrics")
    void buildShouldExceptionIfMetricEnabledWithNullMetrics() {
        // given
        final ThreadManager threadManager = mock(ThreadManager.class);
        final InterruptableConsumer<String> handler = mock(InterruptableConsumer.class);

        // then
        assertThatThrownBy(() -> new DummyQueueThreadConfiguration<String>(threadManager)
                        .setNodeId(NODE_ID)
                        .setComponent(THREAD_POOL_NAME)
                        .setThreadName(THREAD_NAME)
                        .setMaxBufferSize(MAX_BUFFER_SIZE)
                        .setCapacity(CAPACITY)
                        .setHandler(handler)
                        .setMetricsConfiguration(new QueueThreadMetricsConfiguration(null))
                        .buildQueueThread(false))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new DummyQueueThreadConfiguration<String>(threadManager)
                        .setNodeId(NODE_ID)
                        .setComponent(THREAD_POOL_NAME)
                        .setThreadName(THREAD_NAME)
                        .setMaxBufferSize(MAX_BUFFER_SIZE)
                        .setCapacity(CAPACITY)
                        .setHandler(handler)
                        .setMetricsConfiguration(new QueueThreadMetricsConfiguration(null))
                        .buildQueueThread(false))
                .isInstanceOf(NullPointerException.class);
    }
}
