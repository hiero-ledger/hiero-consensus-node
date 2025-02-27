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
import com.swirlds.metrics.api.Metrics;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Testing MeasuredBlockingQueue.Config")
class MeasuredBlockingQueueConfigTest {

    static final String CATEGORY = "myCategory";
    static final String QUEUE_NAME = "myName";

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
    @DisplayName("Testing config constructor with max size metric enabled")
    void configConstructorWithMaxSizeEnabled() {
        final MeasuredBlockingQueue.Config config =
                new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME).withMaxSizeMetricEnabled(true);

        assertThat(config.getMetrics()).isEqualTo(metrics);
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getQueueName()).isEqualTo(QUEUE_NAME);
        assertThat(config.isMaxSizeMetricEnabled()).isTrue();
        assertThat(config.isMinSizeMetricEnabled()).isFalse();
        assertThat(config.isMetricEnabled()).isTrue();
    }

    @Test
    @DisplayName("Testing config constructor with min size metric enabled")
    void configConstructorWithMinSizeEnabled() {
        final MeasuredBlockingQueue.Config config =
                new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME).withMinSizeMetricEnabled(true);

        assertThat(config.getMetrics()).isEqualTo(metrics);
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getQueueName()).isEqualTo(QUEUE_NAME);
        assertThat(config.isMaxSizeMetricEnabled()).isFalse();
        assertThat(config.isMinSizeMetricEnabled()).isTrue();
        assertThat(config.isMetricEnabled()).isTrue();
    }

    @Test
    @DisplayName("Testing config constructor with no metric enabled")
    void configConstructorWithNoMetricEnabled() {
        final MeasuredBlockingQueue.Config config = new MeasuredBlockingQueue.Config(metrics, CATEGORY, QUEUE_NAME);

        assertThat(config.getMetrics()).isEqualTo(metrics);
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getQueueName()).isEqualTo(QUEUE_NAME);
        assertThat(config.isMaxSizeMetricEnabled()).isFalse();
        assertThat(config.isMinSizeMetricEnabled()).isFalse();
        assertThat(config.isMetricEnabled()).isFalse();
    }

    @Test
    @DisplayName("Testing config constructor with invalid parameter")
    void configConstructorWithInvalidParameter() {
        assertThatThrownBy(() -> new MeasuredBlockingQueue.Config(null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MeasuredBlockingQueue.Config(null, CATEGORY, QUEUE_NAME))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MeasuredBlockingQueue.Config(metrics, null, QUEUE_NAME))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MeasuredBlockingQueue.Config(metrics, CATEGORY, null))
                .isInstanceOf(NullPointerException.class);
    }
}
