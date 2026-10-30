// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.hashgraph.impl.metrics;

import static com.swirlds.metrics.api.Metrics.PLATFORM_CATEGORY;
import static org.assertj.core.api.Assertions.assertThat;

import com.hedera.hapi.platform.state.ConsensusSnapshot;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.base.test.fixtures.time.FakeTime;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import com.swirlds.metrics.api.Metric;
import com.swirlds.metrics.api.Metric.ValueType;
import com.swirlds.metrics.api.Metrics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import org.hiero.consensus.metrics.config.ConsensusLayerMetricsConfig_;
import org.hiero.consensus.metrics.config.MetricsConfig;
import org.hiero.consensus.metrics.platform.DefaultPlatformMetrics;
import org.hiero.consensus.metrics.platform.MetricKeyRegistry;
import org.hiero.consensus.metrics.platform.PlatformMetricsFactoryImpl;
import org.hiero.consensus.model.event.EventOrigin;
import org.hiero.consensus.model.event.PlatformEvent;
import org.hiero.consensus.model.event.UnsignedEvent;
import org.hiero.consensus.model.hashgraph.ConsensusRound;
import org.hiero.consensus.model.hashgraph.EventWindow;
import org.hiero.consensus.model.node.NodeId;
import org.hiero.consensus.model.test.fixtures.event.TestingEventBuilder;
import org.hiero.consensus.model.test.fixtures.roster.RosterWrapperFactory;
import org.hiero.consensus.test.fixtures.Randotron;
import org.junit.jupiter.api.Test;

/**
 * Checks the latency gauges of {@link ConsensusMetricsImpl}.
 */
class ConsensusMetricsImplTest {

    /** The step of the gauges over the default window of 5 minutes: 300 s in 20 snapshots, at the interval of 3 s. */
    private static final Duration STEP = Duration.ofSeconds(15);

    private final Random random = Randotron.create();
    private final FakeTime time = new FakeTime();

    @Test
    void receivedToConsensusCoversOnlyGossipedEvents() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final Metrics metrics = newMetrics(configuration);
        final ConsensusMetrics consensusMetrics = new ConsensusMetricsImpl(NodeId.of(0), metrics, configuration, time);
        final PlatformEvent gossiped = newEvent(EventOrigin.GOSSIP, time.now());
        final PlatformEvent own = newEvent(EventOrigin.RUNTIME, time.now().minusSeconds(1));
        final PlatformEvent replayed = newEvent(EventOrigin.STORAGE, time.now().minusSeconds(1));

        time.tick(Duration.ofMillis(2));
        consensusMetrics.consensusRoundEmitted(newRound(gossiped, own, replayed));
        time.tick(STEP);

        assertThat((Double) metrics.getMetric(PLATFORM_CATEGORY, "eventLatency_R2C_max")
                        .get(ValueType.VALUE))
                .as("from receiving the gossiped event, without the own and the replayed event")
                .isBetween(2_000.0, 2_000.0 * (1 + 1.0 / 128));
        assertThat(metrics.getMetric(PLATFORM_CATEGORY, "eventLatency_R2C_max").getDescription())
                .endsWith("over the last 300 s");
    }

    @Test
    void transactionLatencyCoversOnlyTheTransactionsOfOwnEvents() {
        final Configuration configuration = new TestConfigBuilder().getOrCreateConfig();
        final Metrics metrics = newMetrics(configuration);
        final ConsensusMetrics consensusMetrics = new ConsensusMetricsImpl(NodeId.of(0), metrics, configuration, time);
        final PlatformEvent own =
                newOwnEvent(time.now().minusMillis(3), time.now().minusMillis(1));
        final PlatformEvent gossiped = newEvent(EventOrigin.GOSSIP, time.now().minusSeconds(1));

        time.tick(Duration.ofMillis(2));
        consensusMetrics.consensusRoundEmitted(newRound(own, gossiped));
        time.tick(STEP);

        assertThat((Double) metrics.getMetric(PLATFORM_CATEGORY, "transactionLatency_total_max")
                        .get(ValueType.VALUE))
                .as("from the earlier transaction entering the pool, without the gossiped event")
                .isBetween(5_000.0, 5_000.0 * (1 + 1.0 / 128));
        assertThat(metrics.getMetric(PLATFORM_CATEGORY, "transactionLatency_total_max")
                        .getDescription())
                .endsWith("over the last 300 s");
        assertThat(metrics.getMetric(PLATFORM_CATEGORY, "transactionLatency_total_p99"))
                .as("the window of 5 minutes holds too few transactions for p99")
                .isNull();
    }

    @Test
    void percentilesComeFromTheConfiguration() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(ConsensusLayerMetricsConfig_.LATENCY_PERCENTILES, "75")
                .withValue(ConsensusLayerMetricsConfig_.TRANSACTION_LATENCY_PERCENTILES, "99")
                .getOrCreateConfig();
        final Metrics metrics = newMetrics(configuration);
        new ConsensusMetricsImpl(NodeId.of(0), metrics, configuration, time);

        assertThat(metrics.getAll())
                .extracting(Metric::getName)
                .filteredOn(name -> name.startsWith("eventLatency_R2C") || name.startsWith("transactionLatency_"))
                .containsExactlyInAnyOrder(
                        "eventLatency_R2C_p75",
                        "eventLatency_R2C_max",
                        "transactionLatency_total_p99",
                        "transactionLatency_total_max");
    }

    @Test
    void noLatencyGaugesIfTheyAreDisabled() {
        final Configuration configuration = new TestConfigBuilder()
                .withValue(ConsensusLayerMetricsConfig_.LATENCY_ENABLED, false)
                .getOrCreateConfig();
        final Metrics metrics = newMetrics(configuration);
        final ConsensusMetrics consensusMetrics = new ConsensusMetricsImpl(NodeId.of(0), metrics, configuration, time);

        consensusMetrics.consensusRoundEmitted(
                newRound(newEvent(EventOrigin.GOSSIP, time.now()), newOwnEvent(time.now())));

        assertThat(metrics.getMetric(PLATFORM_CATEGORY, "eventLatency_R2C_max")).isNull();
        assertThat(metrics.getMetric(PLATFORM_CATEGORY, "transactionLatency_total_max"))
                .isNull();
    }

    private PlatformEvent newEvent(final EventOrigin origin, final Instant received) {
        final PlatformEvent event =
                new TestingEventBuilder(random).setOrigin(origin).build();
        event.setTimeReceived(received);
        return event;
    }

    private PlatformEvent newOwnEvent(final Instant... transactionsReceived) {
        final List<Bytes> transactions = new ArrayList<>();
        for (int i = 0; i < transactionsReceived.length; i++) {
            transactions.add(Bytes.wrap(new byte[] {(byte) i}));
        }
        final UnsignedEvent unsignedEvent = new UnsignedEvent(
                NodeId.of(0), List.of(), 1, time.now(), transactions, 0, time.now(), List.of(transactionsReceived));
        return new PlatformEvent(unsignedEvent, Bytes.EMPTY, EventOrigin.RUNTIME);
    }

    private ConsensusRound newRound(final PlatformEvent... events) {
        return new ConsensusRound(
                RosterWrapperFactory.randomRoster(random, 1),
                List.of(events),
                EventWindow.getGenesisEventWindow(),
                ConsensusSnapshot.DEFAULT,
                false,
                time.now());
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
