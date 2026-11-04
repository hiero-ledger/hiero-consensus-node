// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.event.creator.impl.tipset;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hiero.consensus.event.creator.impl.tipset.TipsetEventCreatorTestUtils.buildEventCreator;

import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metric.ValueType;
import com.swirlds.metrics.api.Metrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import org.hiero.consensus.event.creator.impl.EventCreator;
import org.hiero.consensus.fakes.noop.NoOpMetrics;
import org.hiero.consensus.metrics.config.ConsensusLayerMetricsConfig_;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory;
import org.hiero.consensus.model.transaction.TimestampedTransaction;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.Test;

/**
 * Checks what the tipset event creator measures of the latency of transactions.
 */
class TransactionLatencyTests {

    /** The step of the gauges over the default window of 5 minutes: 300 s in 20 snapshots, at the interval of 3 s. */
    private static final Duration STEP = Duration.ofSeconds(15);

    private final Random random = Randotron.create();
    private final FakeTime time = new FakeTime();

    @Test
    void newEventCarriesTheReceivedTimesOfTransactionsWithoutPriority() {
        final Instant received = time.now().minusMillis(3);
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final EventCreator creator = newEventCreator(
                configuration,
                new NoOpMetrics(),
                List.of(
                        new TimestampedTransaction(Bytes.wrap(new byte[] {1}), received, false),
                        new TimestampedTransaction(
                                Bytes.wrap(new byte[] {2}), time.now().minusSeconds(1), true),
                        new TimestampedTransaction(Bytes.wrap(new byte[] {3}), received.plusMillis(1), false)));

        final PlatformEvent event = creator.maybeCreateEvent();

        assertThat(event).isNotNull();
        assertThat(event.getTransactionCount()).isEqualTo(3);
        assertThat(event.getTransactionReceivedTimes())
                .describedAs("A new event should carry the received times of its transactions without priority")
                .containsExactly(received, received.plusMillis(1));
    }

    @Test
    void poolWaitCoversOnlyTransactionsWithoutPriority() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final Metrics metrics = newMetrics(configuration);
        final EventCreator creator = newEventCreator(
                configuration,
                metrics,
                List.of(
                        new TimestampedTransaction(
                                Bytes.wrap(new byte[] {1}), time.now().minusMillis(3), false),
                        new TimestampedTransaction(
                                Bytes.wrap(new byte[] {2}), time.now().minusSeconds(1), true)));

        assertThat(creator.maybeCreateEvent()).isNotNull();
        time.tick(STEP);

        assertThat((Double) metrics.getMetric(PLATFORM_CATEGORY, "transactionLatency_poolWait_max")
                        .get(ValueType.VALUE))
                .as("from the transaction without priority entering the pool until the creator took it")
                .isBetween(3_000.0, 3_000.0 * (1 + 1.0 / 128));
        assertThat(metrics.getMetric(PLATFORM_CATEGORY, "transactionLatency_poolWait_max")
                        .getDescription())
                .endsWith("over the last 300 s");
    }

    @Test
    void poolWaitPercentilesComeFromTheConfiguration() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(ConsensusLayerMetricsConfig_.TRANSACTION_LATENCY_PERCENTILES, "99")
                .getOrCreateConfig();
        final Metrics metrics = newMetrics(configuration);
        newEventCreator(configuration, metrics, List.of());

        assertThat(metrics.getMetric(PLATFORM_CATEGORY, "transactionLatency_poolWait_p99"))
                .isNotNull();
        assertThat(metrics.getMetric(PLATFORM_CATEGORY, "transactionLatency_poolWait_p50"))
                .isNull();
    }

    @Test
    void noPoolWaitGaugesIfTheyAreDisabled() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(ConsensusLayerMetricsConfig_.LATENCY_ENABLED, false)
                .getOrCreateConfig();
        final Metrics metrics = newMetrics(configuration);
        final EventCreator creator = newEventCreator(
                configuration,
                metrics,
                List.of(new TimestampedTransaction(
                        Bytes.wrap(new byte[] {1}), time.now().minusMillis(3), false)));

        assertThat(creator.maybeCreateEvent()).isNotNull();

        assertThat(metrics.getMetric(PLATFORM_CATEGORY, "transactionLatency_poolWait_max"))
                .isNull();
    }

    private EventCreator newEventCreator(
            final Configuration configuration, final Metrics metrics, final List<TimestampedTransaction> transactions) {
        return buildEventCreator(
                random,
                time,
                RosterWrapperFactory.randomRoster(random, 1),
                NodeId.of(0),
                () -> transactions,
                configuration,
                metrics);
    }

    private static Metrics newMetrics(final Configuration configuration) {
        final MetricsConfig metricsConfig = configuration.getConfigData(MetricsConfig.class);
        return new DefaultPlatformMetrics(
                null,
                new MetricKeyRegistry(),
                Executors.newSingleThreadScheduledExecutor(),
                new PlatformMetricsFactoryImpl(metricsConfig),
                metricsConfig);
    }
}
