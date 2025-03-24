// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics.platform;

import static com.swirlds.common.metrics.platform.MetricsEvent.Type.ADDED;
import static com.swirlds.common.metrics.platform.MetricsEvent.Type.REMOVED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mock.Strictness.LENIENT;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.swirlds.common.metrics.PlatformMetricsFactory;
import com.swirlds.common.metrics.config.MetricsConfig;
import com.swirlds.common.metrics.config.MetricsConfig_;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.IntegerGauge;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.MetricConfig;
import java.util.Collection;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import org.hiero.consensus.model.node.NodeId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DefaultMetricsTest {

    private static final NodeId NODE_ID = NodeId.of(42L);
    private static final String CATEGORY_1 = "CaTeGoRy1";
    private static final String CATEGORY_1a = "CaTeGoRy1.a";
    private static final String CATEGORY_1b = "CaTeGoRy1.b";
    private static final String CATEGORY_2 = "CaTeGoRy2";
    private static final String CATEGORY_11 = "CaTeGoRy11";
    private static final String NAME_1 = "NaMe1";
    private static final String NAME_2 = "NaMe2";

    @Mock
    private MetricKeyRegistry registry;

    @Mock(strictness = LENIENT)
    private ScheduledExecutorService executor;

    @Mock
    private PlatformMetricsFactory factory;

    @Mock
    private Consumer<MetricsEvent> subscriber;

    private DefaultPlatformMetrics metrics;
    private MetricsConfig metricsConfig;

    @Mock(strictness = LENIENT)
    private Counter counter_1_1;

    @Mock(strictness = LENIENT)
    private Counter counter_1a_1;

    @Mock(strictness = LENIENT)
    private Counter counter_1b_1;

    @Mock(strictness = LENIENT)
    private Counter counter_1_2;

    @Mock(strictness = LENIENT)
    private Counter counter_2_1;

    @Mock(strictness = LENIENT)
    private Counter counter_11_1;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setupService() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(MetricsConfig_.METRICS_UPDATE_PERIOD_MILLIS, 10L)
                .getOrCreateConfig();
        metricsConfig = configuration.getConfigData(MetricsConfig.class);

        when(registry.register(any(), any(), any())).thenReturn(true);

        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(executor)
                .execute(any());
        doAnswer(invocation -> {
                    invocation.getArgument(0, Runnable.class).run();
                    return null;
                })
                .when(executor)
                .scheduleAtFixedRate(any(), anyLong(), anyLong(), any());

        metrics = new DefaultPlatformMetrics(NODE_ID, registry, executor, factory, metricsConfig);
        setupDefaultData();
        metrics.subscribe(subscriber);
        reset(subscriber);
    }

    private void setupDefaultData() {
        when(counter_1_1.getCategory()).thenReturn(CATEGORY_1);
        when(counter_1_1.getName()).thenReturn(NAME_1);
        when(counter_1_1.toString()).thenReturn(String.format("Counter(%s, %s)", CATEGORY_1, NAME_1));

        when(counter_1a_1.getCategory()).thenReturn(CATEGORY_1a);
        when(counter_1a_1.getName()).thenReturn(NAME_1);
        when(counter_1a_1.toString()).thenReturn(String.format("Counter(%s, %s)", CATEGORY_1a, NAME_1));

        when(counter_1b_1.getCategory()).thenReturn(CATEGORY_1b);
        when(counter_1b_1.getName()).thenReturn(NAME_1);
        when(counter_1b_1.toString()).thenReturn(String.format("Counter(%s, %s)", CATEGORY_1b, NAME_1));

        when(counter_1_2.getCategory()).thenReturn(CATEGORY_1);
        when(counter_1_2.getName()).thenReturn(NAME_2);
        when(counter_1_2.toString()).thenReturn(String.format("Counter(%s, %s)", CATEGORY_1, NAME_2));

        when(counter_2_1.getCategory()).thenReturn(CATEGORY_2);
        when(counter_2_1.getName()).thenReturn(NAME_1);
        when(counter_2_1.toString()).thenReturn(String.format("Counter(%s, %s)", CATEGORY_2, NAME_1));

        when(counter_11_1.getCategory()).thenReturn(CATEGORY_11);
        when(counter_11_1.getName()).thenReturn(NAME_1);
        when(counter_11_1.toString()).thenReturn(String.format("Counter(%s, %s)", CATEGORY_11, NAME_1));

        when(factory.createMetric(any()))
                .thenReturn(counter_1_1, counter_1a_1, counter_1b_1, counter_1_2, counter_2_1, counter_11_1);
        final Counter created_1_1 = metrics.getOrCreate(new Counter.Config(CATEGORY_1, NAME_1));
        final Counter created_1a_1 = metrics.getOrCreate(new Counter.Config(CATEGORY_1a, NAME_1));
        final Counter created_1b_1 = metrics.getOrCreate(new Counter.Config(CATEGORY_1b, NAME_1));
        final Counter created_1_2 = metrics.getOrCreate(new Counter.Config(CATEGORY_1, NAME_2));
        final Counter created_2_1 = metrics.getOrCreate(new Counter.Config(CATEGORY_2, NAME_1));
        final Counter created_11_1 = metrics.getOrCreate(new Counter.Config(CATEGORY_11, NAME_1));
        assertThat(created_1_1).isSameAs(counter_1_1);
        assertThat(created_1a_1).isSameAs(counter_1a_1);
        assertThat(created_1b_1).isSameAs(counter_1b_1);
        assertThat(created_1_2).isSameAs(counter_1_2);
        assertThat(created_2_1).isSameAs(counter_2_1);
        assertThat(created_11_1).isSameAs(counter_11_1);
    }

    @Test
    void testConstructorWithNullParameter() {
        assertThatCode(() -> new DefaultPlatformMetrics(null, registry, executor, factory, metricsConfig))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new DefaultPlatformMetrics(NODE_ID, null, executor, factory, metricsConfig))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefaultPlatformMetrics(NODE_ID, registry, null, factory, metricsConfig))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DefaultPlatformMetrics(NODE_ID, registry, executor, null, metricsConfig))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testGetSingleMetricThatExists() {
        // when
        final Metric actual = metrics.getMetric(CATEGORY_1, NAME_1);

        // then
        assertThat(actual).isEqualTo(counter_1_1);
    }

    @Test
    void testGetSingleMetricThatDoesNotExists() {
        // when
        final Metric actual = metrics.getMetric(CATEGORY_2, NAME_2);

        // then
        assertThat(actual).isNull();
    }

    @Test
    void testGetSingleMetricWithNullParameter() {
        assertThatThrownBy(() -> metrics.getMetric(null, NAME_1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> metrics.getMetric(CATEGORY_1, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testGetMetricsOfCategory() {
        // when
        final Collection<Metric> actual = metrics.findMetricsByCategory(CATEGORY_1);

        // then
        assertThat(actual).containsExactly(counter_1_1, counter_1_2, counter_1a_1, counter_1b_1);
    }

    @Test
    void testGetMetricsOfNonExistingCategory() {
        // when
        final Collection<Metric> actual = metrics.findMetricsByCategory("NonExistingCategory");

        // then
        assertThat(actual).isEmpty();
    }

    @Test
    void testGetMetricsOfCategoryAfterMetricWasAdded(@Mock final Counter newCounter) {
        // given
        final Collection<Metric> actual = metrics.findMetricsByCategory(CATEGORY_1);
        when(factory.createMetric(any())).thenReturn(newCounter);

        // when
        final Counter newCreated = metrics.getOrCreate(new Counter.Config(CATEGORY_1, "New Counter"));

        // then
        assertThat(newCreated).isSameAs(newCounter);
        assertThat(actual).containsExactly(counter_1_1, counter_1_2, newCounter, counter_1a_1, counter_1b_1);
    }

    @Test
    void testGetMetricsOfCategoryAfterMetricWasRemoved() {
        // given
        final Collection<Metric> actual = metrics.findMetricsByCategory(CATEGORY_1);

        // when
        metrics.remove(counter_1_1);

        // then
        assertThat(actual).containsExactly(counter_1_2, counter_1a_1, counter_1b_1);
    }

    @Test
    void testGetMetricsOfCategoryWithNullParameter() {
        assertThatThrownBy(() -> metrics.findMetricsByCategory(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testGetAllMetrics() {
        // when
        final Collection<Metric> actual = metrics.getAll();

        // then
        assertThat(actual)
                .containsExactly(counter_1_1, counter_1_2, counter_1a_1, counter_1b_1, counter_11_1, counter_2_1);
    }

    @Test
    void testGetAllMetricsAfterMetricWasAdded(@Mock final Counter newCounter) {
        // given
        final Collection<Metric> actual = metrics.getAll();
        when(factory.createMetric(any())).thenReturn(newCounter);

        // when
        final Counter newCreated = metrics.getOrCreate(new Counter.Config(CATEGORY_1, "New Counter"));

        // then
        assertThat(newCreated).isSameAs(newCounter);
        assertThat(actual)
                .containsExactly(
                        counter_1_1, counter_1_2, newCounter, counter_1a_1, counter_1b_1, counter_11_1, counter_2_1);
    }

    @Test
    void testGetAllMetricsAfterMetricWasRemoved() {
        // given
        final Collection<Metric> actual = metrics.getAll();

        // when
        metrics.remove(counter_1_1);

        // then
        assertThat(actual).containsExactly(counter_1_2, counter_1a_1, counter_1b_1, counter_11_1, counter_2_1);
    }

    @Test
    void testReset() {
        // when
        metrics.resetAll();

        // then
        verify(counter_1_1, atLeastOnce()).reset();
    }

    @Test
    void testSubscribeAfterAdd(@Mock final Consumer<MetricsEvent> secondSubscriber) {
        // given
        final Metric metric = metrics.getOrCreate(new Counter.Config(CATEGORY_1, "New Counter"));

        // when
        metrics.subscribe(secondSubscriber);

        // then
        verify(secondSubscriber, atLeastOnce()).accept(new MetricsEvent(ADDED, NODE_ID, metric));
    }

    @Test
    void testSubscribeBeforeAdd() {
        // when
        final Metric metric = metrics.getOrCreate(new Counter.Config(CATEGORY_1, "New Counter"));

        // then
        verify(subscriber, atLeastOnce()).accept(new MetricsEvent(ADDED, NODE_ID, metric));
    }

    @Test
    void testSubscribeAfterRemoveMetricKey(@Mock final Consumer<MetricsEvent> secondSubscriber) {
        // given
        final Metric metric = metrics.getOrCreate(new Counter.Config(CATEGORY_1, NAME_1));
        metrics.remove(CATEGORY_1, NAME_1);

        // when
        metrics.subscribe(secondSubscriber);

        // then
        verify(secondSubscriber, never()).accept(new MetricsEvent(REMOVED, NODE_ID, metric));
    }

    @Test
    void testSubscribeBeforeRemoveMetricKey() {
        // given
        final Metric metric = metrics.getOrCreate(new Counter.Config(CATEGORY_1, NAME_1));

        // when
        metrics.remove(CATEGORY_1, NAME_1);

        // then
        verify(subscriber).accept(new MetricsEvent(REMOVED, NODE_ID, metric));
    }

    @Test
    void testSubscribeAfterRemoveMetric(@Mock final Consumer<MetricsEvent> secondSubscriber) {
        // given
        final Metric metric = metrics.getOrCreate(new Counter.Config(CATEGORY_1, NAME_1));
        metrics.remove(metric);

        // when
        metrics.subscribe(secondSubscriber);

        // then
        verify(secondSubscriber, never()).accept(new MetricsEvent(REMOVED, NODE_ID, metric));
    }

    @Test
    void testSubscribeBeforeRemoveMetric() {
        // given
        final Metric metric = metrics.getOrCreate(new Counter.Config(CATEGORY_1, NAME_1));

        // when
        metrics.remove(metric);

        // then
        verify(subscriber).accept(new MetricsEvent(REMOVED, NODE_ID, metric));
    }

    @Test
    void testSubscribeAfterRemoveMetricConfig(@Mock final Consumer<MetricsEvent> secondSubscriber) {
        // given
        final Counter.Config config = new Counter.Config(CATEGORY_1, NAME_1);
        final Metric metric = metrics.getOrCreate(config);
        metrics.remove(config);

        // when
        metrics.subscribe(secondSubscriber);

        // then
        verify(secondSubscriber, never()).accept(new MetricsEvent(REMOVED, NODE_ID, metric));
    }

    @Test
    void testSubscribeBeforeRemoveMetricConfig() {
        // given
        final Counter.Config config = new Counter.Config(CATEGORY_1, NAME_1);
        final Metric metric = metrics.getOrCreate(config);

        // when
        metrics.remove(config);

        // then
        verify(subscriber).accept(new MetricsEvent(REMOVED, NODE_ID, metric));
    }

    @Test
    void testCreateDuplicateMetric() {
        // when
        final Counter actual = metrics.getOrCreate(new Counter.Config(CATEGORY_1, NAME_1));

        // then
        assertThat(actual).isSameAs(counter_1_1);
        verify(subscriber, never()).accept(any());
    }

    @Test
    void testCreateDuplicateMetricWithWrongType() {
        // given
        final IntegerGauge.Config config = new IntegerGauge.Config(CATEGORY_1, NAME_1);

        // then
        assertThatThrownBy(() -> metrics.getOrCreate(config)).isInstanceOf(IllegalStateException.class);
        verify(subscriber, never()).accept(any());
    }

    @Test
    void testCreateMetricWithNullParameter() {
        // then
        assertThatThrownBy(() -> metrics.getOrCreate(null)).isInstanceOf(NullPointerException.class);
        verify(subscriber, never()).accept(any());
    }

    @Test
    void testCreateMetricWithReservedMetricKey() {
        // given
        final String category = "SomeCategory";
        final String name = "SomeName";
        final String metricKey = DefaultPlatformMetrics.calculateMetricKey(category, name);
        when(registry.register(NODE_ID, metricKey, Counter.class)).thenReturn(false);

        // then
        final Counter.Config config = new Counter.Config(category, name);
        assertThatThrownBy(() -> metrics.getOrCreate(config)).isInstanceOf(IllegalStateException.class);
        verify(subscriber, never()).accept(any());
    }

    @Test
    void testRemoveByNameAndCategory() {
        // when
        metrics.remove(CATEGORY_1, NAME_1);

        // then
        final Collection<Metric> remaining = metrics.getAll();
        assertThat(remaining).containsExactly(counter_1_2, counter_1a_1, counter_1b_1, counter_11_1, counter_2_1);
        verify(subscriber).accept(new MetricsEvent(REMOVED, NODE_ID, counter_1_1));
    }

    @Test
    void testRemoveNonExistingByNameAndCategory() {
        // when
        metrics.remove(CATEGORY_2, NAME_2);

        // then
        final Collection<Metric> remaining = metrics.getAll();
        assertThat(remaining)
                .containsExactly(counter_1_1, counter_1_2, counter_1a_1, counter_1b_1, counter_11_1, counter_2_1);
        verify(subscriber, never()).accept(any());
    }

    @Test
    void testRemoveByNameAndCategoryWithNullParameter() {
        assertThatThrownBy(() -> metrics.remove(null, NAME_1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> metrics.remove(CATEGORY_1, null)).isInstanceOf(NullPointerException.class);
        verify(subscriber, never()).accept(any());
    }

    @Test
    void testRemoveByMetric() {
        // when
        metrics.remove(counter_1_1);

        // then
        final Collection<Metric> remaining = metrics.getAll();
        assertThat(remaining).containsExactly(counter_1_2, counter_1a_1, counter_1b_1, counter_11_1, counter_2_1);
        verify(subscriber).accept(new MetricsEvent(REMOVED, NODE_ID, counter_1_1));
    }

    @Test
    void testRemoveByMetricWithWrongClass(@Mock final IntegerGauge gauge) {
        // given
        when(gauge.getCategory()).thenReturn(CATEGORY_1);
        when(gauge.getName()).thenReturn(NAME_1);

        // when
        metrics.remove(gauge);

        // then
        final Collection<Metric> remaining = metrics.getAll();
        assertThat(remaining)
                .containsExactly(counter_1_1, counter_1_2, counter_1a_1, counter_1b_1, counter_11_1, counter_2_1);
        verify(subscriber, never()).accept(any());
    }

    @Test
    void testRemoveNonExistingByMetric(@Mock final Counter counter) {
        // given
        when(counter.getCategory()).thenReturn(CATEGORY_2);
        when(counter.getName()).thenReturn(NAME_2);

        // when
        metrics.remove(counter);

        // then
        final Collection<Metric> remaining = metrics.getAll();
        assertThat(remaining)
                .containsExactly(counter_1_1, counter_1_2, counter_1a_1, counter_1b_1, counter_11_1, counter_2_1);
        verify(subscriber, never()).accept(any());
    }

    @Test
    void testRemoveByMetricWithNullParameter() {
        assertThatThrownBy(() -> metrics.remove((Metric) null)).isInstanceOf(NullPointerException.class);
        verify(subscriber, never()).accept(any());
    }

    @Test
    void testRemoveByConfig() {
        // when
        metrics.remove(new Counter.Config(CATEGORY_1, NAME_1));

        // then
        final Collection<Metric> remaining = metrics.getAll();
        assertThat(remaining).containsExactly(counter_1_2, counter_1a_1, counter_1b_1, counter_11_1, counter_2_1);
        verify(subscriber).accept(new MetricsEvent(REMOVED, NODE_ID, counter_1_1));
    }

    @Test
    void testRemoveByConfigWithWrongClass() {
        // when
        metrics.remove(new IntegerGauge.Config(CATEGORY_1, NAME_1));

        // then
        final Collection<Metric> remaining = metrics.getAll();
        assertThat(remaining)
                .containsExactly(counter_1_1, counter_1_2, counter_1a_1, counter_1b_1, counter_11_1, counter_2_1);
        verify(subscriber, never()).accept(any());
    }

    @Test
    void testRemoveNonExistingByConfig() {
        // when
        metrics.remove(new Counter.Config(CATEGORY_2, NAME_2));

        // then
        final Collection<Metric> remaining = metrics.getAll();
        assertThat(remaining)
                .containsExactly(counter_1_1, counter_1_2, counter_1a_1, counter_1b_1, counter_11_1, counter_2_1);
        verify(subscriber, never()).accept(any());
    }

    @Test
    void testRemoveByConfigWithNullParameter() {
        assertThatThrownBy(() -> metrics.remove((MetricConfig<?, ?>) null)).isInstanceOf(NullPointerException.class);
        verify(subscriber, never()).accept(any());
    }

    @Test
    void testUpdater(@Mock final Runnable updater) {
        // given
        metrics.addUpdater(updater);

        // then
        verify(updater, never()).run();

        // when
        metrics.start();

        // then
        verify(updater, atLeastOnce()).run();
    }

    @Test
    void testDisabledUpdater(@Mock final Runnable updater) {
        // given
        final Configuration configuration = new TestConfigBuilder()
                .withValue(MetricsConfig_.METRICS_UPDATE_PERIOD_MILLIS, 0L)
                .getOrCreateConfig();
        metricsConfig = configuration.getConfigData(MetricsConfig.class);
        final DefaultPlatformMetrics metrics =
                new DefaultPlatformMetrics(NODE_ID, registry, executor, factory, metricsConfig);
        metrics.addUpdater(updater);

        // when
        metrics.start();

        // then
        verify(updater, never()).run();
    }

    @Test
    void testUpdaterWithNullParameter() {
        assertThatThrownBy(() -> metrics.addUpdater(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testUpdaterAddedAfterStart(@Mock final Runnable updater) {
        // given
        final ScheduledExecutorService executor1 = Executors.newSingleThreadScheduledExecutor();
        final DefaultPlatformMetrics metrics =
                new DefaultPlatformMetrics(NODE_ID, registry, executor1, factory, metricsConfig);
        metrics.start();

        // when
        metrics.addUpdater(updater);

        // then
        verify(updater, timeout(100).atLeastOnce()).run();
    }

    @Test
    void testAddGlobalMetric(@Mock final Counter newCounter) {
        // given
        when(newCounter.getCategory()).thenReturn(CATEGORY_1);
        when(newCounter.getName()).thenReturn("New Counter");
        when(factory.createMetric(any())).thenReturn(newCounter);
        final DefaultPlatformMetrics globalMetric =
                new DefaultPlatformMetrics(null, registry, executor, factory, metricsConfig);
        globalMetric.subscribe(metrics::handleGlobalMetrics);

        // when
        globalMetric.getOrCreate(new Counter.Config(CATEGORY_1, "New Counter"));

        // then
        assertThat(metrics.getMetric(CATEGORY_1, "New Counter")).isEqualTo(newCounter);
        verify(subscriber, never()).accept(any());
    }

    @Test
    void testRemoveGlobalMetric(@Mock final Counter newCounter) {
        // given
        when(newCounter.getCategory()).thenReturn(CATEGORY_1);
        when(newCounter.getName()).thenReturn("New Counter");
        when(factory.createMetric(any())).thenReturn(newCounter);
        final DefaultPlatformMetrics globalMetric =
                new DefaultPlatformMetrics(null, registry, executor, factory, metricsConfig);
        globalMetric.subscribe(metrics::handleGlobalMetrics);
        globalMetric.getOrCreate(new Counter.Config(CATEGORY_1, "New Counter"));

        // when
        globalMetric.remove(CATEGORY_1, "New Counter");

        // then
        assertThat(metrics.getMetric(CATEGORY_1, "New Counter")).isNull();
        verify(subscriber, never()).accept(any());
    }
}
