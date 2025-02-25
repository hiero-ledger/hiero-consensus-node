// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.threading.framework.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import com.swirlds.common.metrics.PlatformMetricsFactory;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.platform.DefaultPlatformMetrics;
import com.swirlds.common.metrics.platform.MetricKeyRegistry;
import com.swirlds.common.metrics.platform.PlatformMetricsFactoryImpl;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.IntegerAccumulator;
import com.swirlds.metrics.api.Metrics;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Testing MeasuredBlockingQueue")
class MeasuredBlockingQueueTest {

    static final String CATEGORY = "myCategory";
    static final String QUEUE_NAME = "myName";
    static final String MAX_SIZE_METRIC_NAME = QUEUE_NAME + MeasuredBlockingQueue.QUEUE_MAX_SIZE_SUFFIX;
    static final String MIN_SIZE_METRIC_NAME = QUEUE_NAME + MeasuredBlockingQueue.QUEUE_MIN_SIZE_SUFFIX;

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
    @DisplayName("Testing constructor with maxSizeMetric")
    void constructorShouldInitializeMaxSizeMetricIfEnabled() {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        final MeasuredBlockingQueue.Config config =
                new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME).withMaxSizeMetricEnabled(true);

        new MeasuredBlockingQueue<>(queue, config);

        final IntegerAccumulator maxSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MAX_SIZE_METRIC_NAME);
        assertThat(maxSizeMetric).isNotNull().isInstanceOf(IntegerAccumulator.class);
    }

    @Test
    @DisplayName("Testing constructor with maxSizeMetric")
    void constructorShouldInitializeMinSizeMetricIfEnabled() {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        final MeasuredBlockingQueue.Config config =
                new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME).withMinSizeMetricEnabled(true);

        new MeasuredBlockingQueue<>(queue, config);

        final IntegerAccumulator minSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MIN_SIZE_METRIC_NAME);
        assertThat(minSizeMetric).isNotNull().isInstanceOf(IntegerAccumulator.class);
    }

    @Test
    @DisplayName("Testing constructor with invalid parameter")
    void constructorShouldThrowExceptionWithInvalidParameter() {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME);

        assertThatThrownBy(() -> new MeasuredBlockingQueue<>(null, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MeasuredBlockingQueue<>(queue, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MeasuredBlockingQueue<>(null, config)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Testing constructor with no enabled metrics")
    void constructorShouldThrowExceptionWithNoEnabledMetrics() {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME);

        assertThatThrownBy(() -> new MeasuredBlockingQueue<>(queue, config)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Testing add")
    void addShouldUpdateQueueMetric() {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("A"));
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME)
                .withMaxSizeMetricEnabled(true)
                .withMinSizeMetricEnabled(true);
        final MeasuredBlockingQueue<String> sut = new MeasuredBlockingQueue<>(queue, config);
        final IntegerAccumulator maxSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MIN_SIZE_METRIC_NAME);

        assertThat(sut.add("B")).isTrue();
        assertThat(sut).hasSize(2);
        assertThat(maxSizeMetric.get()).isEqualTo(2);
        assertThat(minSizeMetric.get()).isEqualTo(1);

        assertThat(sut.add("B")).isTrue();
        assertThat(sut).hasSize(3);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Testing addAll")
    void addAllShouldUpdateQueueMetric() {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("A"));
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME)
                .withMaxSizeMetricEnabled(true)
                .withMinSizeMetricEnabled(true);
        final MeasuredBlockingQueue<String> sut = new MeasuredBlockingQueue<>(queue, config);
        final IntegerAccumulator maxSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MIN_SIZE_METRIC_NAME);

        assertThat(sut.addAll(List.of("B", "C"))).isTrue();
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Testing offer")
    void offerShouldUpdateQueueMetric() {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("A"));
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME)
                .withMaxSizeMetricEnabled(true)
                .withMinSizeMetricEnabled(true);
        final MeasuredBlockingQueue<String> sut = new MeasuredBlockingQueue<>(queue, config);
        final IntegerAccumulator maxSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MIN_SIZE_METRIC_NAME);

        assertThat(sut.offer("B")).isTrue();
        assertThat(sut).hasSize(2);
        assertThat(maxSizeMetric.get()).isEqualTo(2);
        assertThat(minSizeMetric.get()).isEqualTo(1);

        assertThat(sut.offer("C")).isTrue();
        assertThat(sut).hasSize(3);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Testing offer with timeout")
    void offerWithTimeoutShouldUpdateQueueMetric() throws InterruptedException {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("A"));
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME)
                .withMaxSizeMetricEnabled(true)
                .withMinSizeMetricEnabled(true);
        final MeasuredBlockingQueue<String> sut = new MeasuredBlockingQueue<>(queue, config);
        final IntegerAccumulator maxSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MIN_SIZE_METRIC_NAME);

        assertThat(sut.offer("B", 1, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(sut).hasSize(2);
        assertThat(maxSizeMetric.get()).isEqualTo(2);
        assertThat(minSizeMetric.get()).isEqualTo(1);

        assertThat(sut.offer("C", 1, TimeUnit.MILLISECONDS)).isTrue();
        assertThat(sut).hasSize(3);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Testing put")
    void putShouldUpdateQueueMetric() throws InterruptedException {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("A"));
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME)
                .withMaxSizeMetricEnabled(true)
                .withMinSizeMetricEnabled(true);
        final MeasuredBlockingQueue<String> sut = new MeasuredBlockingQueue<>(queue, config);
        final IntegerAccumulator maxSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MIN_SIZE_METRIC_NAME);

        sut.put("B");
        assertThat(sut).hasSize(2);
        assertThat(maxSizeMetric.get()).isEqualTo(2);
        assertThat(minSizeMetric.get()).isEqualTo(1);

        sut.put("C");
        assertThat(sut).hasSize(3);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Testing poll")
    void pollShouldUpdateQueueMetric() throws InterruptedException {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("A", "B", "C"));
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME)
                .withMaxSizeMetricEnabled(true)
                .withMinSizeMetricEnabled(true);
        final MeasuredBlockingQueue<String> sut = new MeasuredBlockingQueue<>(queue, config);
        final IntegerAccumulator maxSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MIN_SIZE_METRIC_NAME);

        assertThat(sut.poll(1, TimeUnit.MILLISECONDS)).isEqualTo("A");
        assertThat(sut).hasSize(2);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(2);

        assertThat(sut.poll(1, TimeUnit.MILLISECONDS)).isEqualTo("B");
        assertThat(sut).hasSize(1);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Testing poll with timeout")
    void pollWithTimeoutShouldUpdateQueueMetric() {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("A", "B", "C"));
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME)
                .withMaxSizeMetricEnabled(true)
                .withMinSizeMetricEnabled(true);
        final MeasuredBlockingQueue<String> sut = new MeasuredBlockingQueue<>(queue, config);
        final IntegerAccumulator maxSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MIN_SIZE_METRIC_NAME);

        assertThat(sut.poll()).isEqualTo("A");
        assertThat(sut).hasSize(2);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(2);

        assertThat(sut.poll()).isEqualTo("B");
        assertThat(sut).hasSize(1);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Testing remove")
    void removeShouldUpdateQueueMetric() {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("A", "B", "C"));
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME)
                .withMaxSizeMetricEnabled(true)
                .withMinSizeMetricEnabled(true);
        final MeasuredBlockingQueue<String> sut = new MeasuredBlockingQueue<>(queue, config);
        final IntegerAccumulator maxSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MIN_SIZE_METRIC_NAME);

        assertThat(sut.remove()).isEqualTo("A");
        assertThat(sut).hasSize(2);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(2);

        assertThat(sut.remove()).isEqualTo("B");
        assertThat(sut).hasSize(1);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Testing remove object")
    void removeObjectShouldUpdateQueueMetric() {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("A", "B", "C"));
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME)
                .withMaxSizeMetricEnabled(true)
                .withMinSizeMetricEnabled(true);
        final MeasuredBlockingQueue<String> sut = new MeasuredBlockingQueue<>(queue, config);
        final IntegerAccumulator maxSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MIN_SIZE_METRIC_NAME);

        assertThat(sut.remove("A")).isTrue();
        assertThat(sut).hasSize(2);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(2);

        assertThat(sut.remove("B")).isTrue();
        assertThat(sut).hasSize(1);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Testing removeAll")
    void removeAllShouldUpdateQueueMetric() {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("A", "B", "C"));
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME)
                .withMaxSizeMetricEnabled(true)
                .withMinSizeMetricEnabled(true);
        final MeasuredBlockingQueue<String> sut = new MeasuredBlockingQueue<>(queue, config);
        final IntegerAccumulator maxSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MIN_SIZE_METRIC_NAME);

        assertThat(sut.removeAll(List.of("A", "B"))).isTrue();
        assertThat(sut).hasSize(1);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Testing take")
    void takeShouldUpdateQueueMetric() throws InterruptedException {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("A", "B", "C"));
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME)
                .withMaxSizeMetricEnabled(true)
                .withMinSizeMetricEnabled(true);
        final MeasuredBlockingQueue<String> sut = new MeasuredBlockingQueue<>(queue, config);
        final IntegerAccumulator maxSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MIN_SIZE_METRIC_NAME);

        assertThat(sut.take()).isEqualTo("A");
        assertThat(sut).hasSize(2);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(2);

        assertThat(sut.take()).isEqualTo("B");
        assertThat(sut).hasSize(1);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Testing retainAll")
    void retainAllShouldUpdateQueueMetric() {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("A", "B", "C"));
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME)
                .withMaxSizeMetricEnabled(true)
                .withMinSizeMetricEnabled(true);
        final MeasuredBlockingQueue<String> sut = new MeasuredBlockingQueue<>(queue, config);
        final IntegerAccumulator maxSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MIN_SIZE_METRIC_NAME);

        assertThat(sut.retainAll(List.of("A", "B"))).isTrue();
        assertThat(sut).hasSize(2);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Testing clear")
    void clearShouldUpdateQueueMetric() {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("A", "B", "C"));
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME)
                .withMaxSizeMetricEnabled(true)
                .withMinSizeMetricEnabled(true);
        final MeasuredBlockingQueue<String> sut = new MeasuredBlockingQueue<>(queue, config);
        final IntegerAccumulator maxSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MIN_SIZE_METRIC_NAME);

        sut.clear();
        assertThat(sut).isEmpty();
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isZero();
    }

    @Test
    @DisplayName("Testing drainTo")
    void drainToShouldUpdateQueueMetric() {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("A", "B", "C"));
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME)
                .withMaxSizeMetricEnabled(true)
                .withMinSizeMetricEnabled(true);
        final MeasuredBlockingQueue<String> sut = new MeasuredBlockingQueue<>(queue, config);
        final IntegerAccumulator maxSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MIN_SIZE_METRIC_NAME);

        final List<String> buffer = new LinkedList<>();
        assertThat(sut.drainTo(buffer)).isEqualTo(3);
        assertThat(sut).isEmpty();
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isZero();
    }

    @Test
    @DisplayName("Testing drainTo with max elements")
    void drainToWithMaxElementsShouldUpdateQueueMetric() {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>(List.of("A", "B", "C"));
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME)
                .withMaxSizeMetricEnabled(true)
                .withMinSizeMetricEnabled(true);
        final MeasuredBlockingQueue<String> sut = new MeasuredBlockingQueue<>(queue, config);
        final IntegerAccumulator maxSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MAX_SIZE_METRIC_NAME);
        final IntegerAccumulator minSizeMetric = (IntegerAccumulator) metrics.getMetric(CATEGORY, MIN_SIZE_METRIC_NAME);

        final List<String> buffer = new LinkedList<>();
        assertThat(sut.drainTo(buffer, 2)).isEqualTo(2);
        assertThat(sut).hasSize(1);
        assertThat(maxSizeMetric.get()).isEqualTo(3);
        assertThat(minSizeMetric.get()).isEqualTo(1);
    }
}
