// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.workflows.handle.metrics;

import static com.swirlds.metrics.api.Metric.ValueType.VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.node.app.utils.TestUtils;
import com.hedera.node.app.workflows.OpWorkflowMetrics;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.VersionedConfigImpl;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.metrics.api.Counter;
import com.swirlds.metrics.api.Metrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpWorkflowMetricsTest {

    private final Metrics metrics = TestUtils.metrics();
    private ConfigProvider configProvider;

    @BeforeEach
    void setUp() {
        configProvider = () -> new VersionedConfigImpl(HederaTestConfigBuilder.createConfig(), 1);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void testConstructorWithInvalidArguments() {
        assertThatThrownBy(() -> new OpWorkflowMetrics(null, configProvider)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OpWorkflowMetrics(metrics, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testConstructorInitializesMetrics() {
        // when
        new OpWorkflowMetrics(metrics, configProvider);

        // then
        // subtract 1 to exclude HederaFunctionality.NONE
        // multiply by 3 to account for max, avg, and throttle metrics which are created for each functionality
        // add 1 to account for gasPerConsSec metric which is not functionality specific
        final int transactionMetricsCount = ((HederaFunctionality.values().length - 1) * 3) + 1;
        assertThat(metrics.findMetricsByCategory("app")).hasSize(transactionMetricsCount);
    }

    @Test
    void testInitialValue() {
        // given
        new OpWorkflowMetrics(metrics, configProvider);

        // then
        assertThat(metrics.getMetric("app", "cryptoCreateDurationMax").get(VALUE))
                .isEqualTo(0);
        assertThat(metrics.getMetric("app", "cryptoCreateDurationAvg").get(VALUE))
                .isEqualTo(0);
        assertThat(metrics.getMetric("app", "cryptoCreateThrottledTxns").get(VALUE))
                .isSameAs(0L);
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void testUpdateDurationWithInvalidArguments() {
        // given
        final var handleWorkflowMetrics = new OpWorkflowMetrics(metrics, configProvider);

        // when
        assertThatThrownBy(() -> handleWorkflowMetrics.updateDuration(null, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testIncrementThrottledWithInvalidArguments() {
        // given
        final var handleWorkflowMetrics = new OpWorkflowMetrics(metrics, configProvider);

        // when
        assertThatThrownBy(() -> handleWorkflowMetrics.incrementThrottled(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testUpdateTransactionDurationSingleUpdate() {
        // given
        final var handleWorkflowMetrics = new OpWorkflowMetrics(metrics, configProvider);

        // when
        handleWorkflowMetrics.updateDuration(HederaFunctionality.CRYPTO_CREATE, 42);

        // then
        assertThat(metrics.getMetric("app", "cryptoCreateDurationMax").get(VALUE))
                .isEqualTo(42);
        assertThat(metrics.getMetric("app", "cryptoCreateDurationAvg").get(VALUE))
                .isEqualTo(42);
    }

    @Test
    void testUpdateDurationTwoUpdates() {
        // given
        final var handleWorkflowMetrics = new OpWorkflowMetrics(metrics, configProvider);

        // when
        handleWorkflowMetrics.updateDuration(HederaFunctionality.CRYPTO_CREATE, 11);
        handleWorkflowMetrics.updateDuration(HederaFunctionality.CRYPTO_CREATE, 22);

        // then
        assertThat(metrics.getMetric("app", "cryptoCreateDurationMax").get(VALUE))
                .isEqualTo(22);
        assertThat(metrics.getMetric("app", "cryptoCreateDurationAvg").get(VALUE))
                .isEqualTo(16);
    }

    @Test
    void testUpdateDurationThreeUpdates() {
        // given
        final var handleWorkflowMetrics = new OpWorkflowMetrics(metrics, configProvider);

        // when
        handleWorkflowMetrics.updateDuration(HederaFunctionality.CRYPTO_CREATE, 13);
        handleWorkflowMetrics.updateDuration(HederaFunctionality.CRYPTO_CREATE, 5);
        handleWorkflowMetrics.updateDuration(HederaFunctionality.CRYPTO_CREATE, 3);

        // then
        assertThat(metrics.getMetric("app", "cryptoCreateDurationMax").get(VALUE))
                .isEqualTo(13);
        assertThat(metrics.getMetric("app", "cryptoCreateDurationAvg").get(VALUE))
                .isEqualTo(7);
    }

    @Test
    void testIncrementThrottled() {
        // given
        final var handleWorkflowMetrics = new OpWorkflowMetrics(metrics, configProvider);

        // when
        handleWorkflowMetrics.incrementThrottled(HederaFunctionality.CRYPTO_CREATE);

        // then
        final var throttledMetric = (Counter) metrics.getMetric("app", "cryptoCreateThrottledTxns");
        assertThat(throttledMetric.get()).isSameAs(1L);
    }

    @Test
    void testInitialStartConsensusRound() {
        // given
        final var handleWorkflowMetrics = new OpWorkflowMetrics(metrics, configProvider);

        // when
        handleWorkflowMetrics.switchConsensusSecond();

        // then
        assertThat((Double) metrics.getMetric("app", "gasPerConsSec").get(VALUE))
                .isCloseTo(0.0, offset(1e-6));
    }

    @Test
    void testUpdateGasZero() {
        // given
        final var handleWorkflowMetrics = new OpWorkflowMetrics(metrics, configProvider);

        // when
        handleWorkflowMetrics.addGasUsed(0L);
        handleWorkflowMetrics.switchConsensusSecond();

        // then
        assertThat((Double) metrics.getMetric("app", "gasPerConsSec").get(VALUE))
                .isCloseTo(0.0, offset(1e-6));
    }

    @Test
    void testUpdateGas() {
        // given
        final var handleWorkflowMetrics = new OpWorkflowMetrics(metrics, configProvider);

        // when
        handleWorkflowMetrics.addGasUsed(1_000_000L);
        handleWorkflowMetrics.switchConsensusSecond();

        // then
        assertThat((Double) metrics.getMetric("app", "gasPerConsSec").get(VALUE))
                .isGreaterThan(0.0);
    }
}
