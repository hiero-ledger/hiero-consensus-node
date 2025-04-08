// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.throttle;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.hedera.node.app.hapi.utils.throttles.DeterministicThrottle;
import com.hedera.node.app.hapi.utils.throttles.LeakyBucketDeterministicThrottle;
import com.hedera.node.app.throttle.ThrottleAccumulator.ThrottleType;
import com.hedera.node.config.testfixtures.HederaTestConfigBuilder;
import com.swirlds.metrics.api.DoubleGauge;
import com.swirlds.metrics.api.Metrics;
import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ThrottleMetricsTest {

    @Mock
    private Metrics metrics;

    @SuppressWarnings("DataFlowIssue")
    @Test
    void constructorWithInvalidArguments() {
        assertThatThrownBy(() -> new ThrottleMetrics(null, ThrottleType.FRONTEND_THROTTLE))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ThrottleMetrics(metrics, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void setupSingleLiveMetricShouldCreateMetric(@Mock DeterministicThrottle throttle) {
        // given
        when(throttle.name()).thenReturn("throttle1");
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("stats.hapiThrottlesToSample", "throttle1")
                .getOrCreateConfig();
        final var throttleMetrics = new ThrottleMetrics(metrics, ThrottleType.FRONTEND_THROTTLE);

        // when
        throttleMetrics.setupThrottleMetrics(List.of(throttle), configuration);

        // then
        verify(metrics).getOrCreate(any(DoubleGauge.Config.class));
    }

    @Test
    void setupTwoLiveMetricsShouldCreateTwoMetrics(
            @Mock DeterministicThrottle throttle1, @Mock DeterministicThrottle throttle2) {
        // given
        when(throttle1.name()).thenReturn("throttle1");
        when(throttle2.name()).thenReturn("throttle2");
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("stats.hapiThrottlesToSample", "throttle1,throttle2")
                .getOrCreateConfig();
        final var throttleMetrics = new ThrottleMetrics(metrics, ThrottleType.FRONTEND_THROTTLE);

        // when
        throttleMetrics.setupThrottleMetrics(List.of(throttle1, throttle2), configuration);

        // then
        verify(metrics, times(2)).getOrCreate(any(DoubleGauge.Config.class));
    }

    @Test
    void setupNonTrackedLiveMetricShouldNotCreateMetric(@Mock DeterministicThrottle throttle) {
        // given
        when(throttle.name()).thenReturn("throttle1");
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("stats.hapiThrottlesToSample", "")
                .getOrCreateConfig();
        final var throttleMetrics = new ThrottleMetrics(metrics, ThrottleType.FRONTEND_THROTTLE);

        // when
        throttleMetrics.setupThrottleMetrics(List.of(throttle), configuration);

        // then
        verify(metrics, never()).getOrCreate(any());
    }

    @Test
    void setupInertMetricShouldCreateMetric() {
        // given
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("stats.hapiThrottlesToSample", "throttle2")
                .getOrCreateConfig();
        final var throttleMetrics = new ThrottleMetrics(metrics, ThrottleType.FRONTEND_THROTTLE);

        // when
        throttleMetrics.setupThrottleMetrics(List.of(), configuration);

        // then
        verify(metrics).getOrCreate(any(DoubleGauge.Config.class));
    }

    @Test
    void setupGasMetricShouldCreateMetric(@Mock LeakyBucketDeterministicThrottle throttle) {
        // given
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("stats.hapiThrottlesToSample", "<GAS>")
                .getOrCreateConfig();
        final var throttleMetrics = new ThrottleMetrics(metrics, ThrottleType.FRONTEND_THROTTLE);

        // when
        throttleMetrics.setupGasThrottleMetric(throttle, configuration);

        // then
        verify(metrics).getOrCreate(any(DoubleGauge.Config.class));
    }

    @Test
    void setupNonTrackedGasMetricShouldNotCreateMetric(@Mock LeakyBucketDeterministicThrottle throttle) {
        // given
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("stats.hapiThrottlesToSample", "")
                .getOrCreateConfig();
        final var throttleMetrics = new ThrottleMetrics(metrics, ThrottleType.FRONTEND_THROTTLE);

        // when
        throttleMetrics.setupGasThrottleMetric(throttle, configuration);

        // then
        verify(metrics, never()).getOrCreate(any());
    }

    @Test
    void updateWithoutMetricsDoesNotFail() {
        // given
        final var throttleMetrics = new ThrottleMetrics(metrics, ThrottleType.FRONTEND_THROTTLE);

        // then
        Assertions.assertThatCode(throttleMetrics::updateAllMetrics).doesNotThrowAnyException();
    }

    @Test
    void updateSingleMetricSucceeds(@Mock DeterministicThrottle throttle, @Mock DoubleGauge gauge) {
        // given
        when(throttle.name()).thenReturn("throttle1");
        when(throttle.instantaneousPercentUsed()).thenReturn(Math.PI);
        when(metrics.getOrCreate(any(DoubleGauge.Config.class))).thenReturn(gauge);
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("stats.hapiThrottlesToSample", "throttle1")
                .getOrCreateConfig();
        final var throttleMetrics = new ThrottleMetrics(metrics, ThrottleType.FRONTEND_THROTTLE);
        throttleMetrics.setupThrottleMetrics(List.of(throttle), configuration);

        // when
        throttleMetrics.updateAllMetrics();

        // then
        verify(gauge).set(Math.PI);
    }

    @Test
    void updateTwoMetricsSucceeds(
            @Mock DeterministicThrottle throttle1,
            @Mock DoubleGauge gauge1,
            @Mock DeterministicThrottle throttle2,
            @Mock DoubleGauge gauge2) {
        // given
        when(throttle1.name()).thenReturn("throttle1");
        when(throttle1.instantaneousPercentUsed()).thenReturn(Math.E);
        when(throttle2.name()).thenReturn("throttle2");
        when(throttle2.instantaneousPercentUsed()).thenReturn(-Math.E);
        when(metrics.getOrCreate(any(DoubleGauge.Config.class))).thenReturn(gauge1, gauge2);
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("stats.hapiThrottlesToSample", "throttle1,throttle2")
                .getOrCreateConfig();
        final var throttleMetrics = new ThrottleMetrics(metrics, ThrottleType.FRONTEND_THROTTLE);
        throttleMetrics.setupThrottleMetrics(List.of(throttle1, throttle2), configuration);

        // when
        throttleMetrics.updateAllMetrics();

        // then
        verify(gauge1).set(Math.E);
        verify(gauge2).set(-Math.E);
    }

    @Test
    void updateGasMetricSucceeds(@Mock LeakyBucketDeterministicThrottle gasThrottle, @Mock DoubleGauge gauge) {
        // given
        when(gasThrottle.instantaneousPercentUsed()).thenReturn(-Math.PI);
        when(metrics.getOrCreate(any(DoubleGauge.Config.class))).thenReturn(gauge);
        final var configuration = HederaTestConfigBuilder.create()
                .withValue("stats.hapiThrottlesToSample", "<GAS>")
                .getOrCreateConfig();
        final var throttleMetrics = new ThrottleMetrics(metrics, ThrottleType.FRONTEND_THROTTLE);
        throttleMetrics.setupGasThrottleMetric(gasThrottle, configuration);

        // when
        throttleMetrics.updateAllMetrics();

        // then
        verify(gauge).set(-Math.PI);
    }
}
