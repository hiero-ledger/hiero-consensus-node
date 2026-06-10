// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class StatisticsProbeTest {

    @Test
    void emptyProbe_returnsNil() {
        final StatisticsProbe probe = new StatisticsProbe("p", ObsUnit.NANOS);
        final Statistics stats = probe.aggregate();

        assertThat(stats).isSameAs(FixedStatistics.NIL);
    }

    @Test
    void singleValue() {
        final StatisticsProbe probe = new StatisticsProbe("p", ObsUnit.BYTES);
        probe.add(42L);
        final Statistics stats = probe.aggregate();

        assertThat(stats.numSamples()).isEqualTo(BigInteger.ONE);
        assertThat(stats.sum()).isEqualTo(BigInteger.valueOf(42));
        assertThat(stats.min()).isEqualTo(BigInteger.valueOf(42));
        assertThat(stats.max()).isEqualTo(BigInteger.valueOf(42));
        assertThat(stats.avg().longValue()).isEqualTo(42);
        assertThat(stats.stdDev()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void multipleValues_computesCorrectAggregates() {
        final StatisticsProbe probe = new StatisticsProbe("p", ObsUnit.NANOS);
        probe.add(10L);
        probe.add(20L);
        probe.add(30L);
        final Statistics stats = probe.aggregate();

        assertThat(stats.numSamples()).isEqualTo(BigInteger.valueOf(3));
        assertThat(stats.sum()).isEqualTo(BigInteger.valueOf(60));
        assertThat(stats.min()).isEqualTo(BigInteger.valueOf(10));
        assertThat(stats.max()).isEqualTo(BigInteger.valueOf(30));
        assertThat(stats.avg().longValue()).isEqualTo(20);
    }

    @Test
    void stdDev_computedCorrectly() {
        // Classic example: [2, 4, 4, 4, 5, 5, 7, 9], population stdDev = 2
        final StatisticsProbe probe = new StatisticsProbe("p", ObsUnit.NANOS);
        for (final long v : new long[] {2, 4, 4, 4, 5, 5, 7, 9}) {
            probe.add(v);
        }
        final Statistics stats = probe.aggregate();

        assertThat(stats.avg().doubleValue()).isCloseTo(5.0, within(0.0001));
        assertThat(stats.stdDev().doubleValue()).isCloseTo(2.0, within(0.0001));
    }

    @Test
    void aggregate_isIdempotent() {
        final StatisticsProbe probe = new StatisticsProbe("p", ObsUnit.COUNT);
        probe.add(7L);
        final Statistics first = probe.aggregate();
        final Statistics second = probe.aggregate();

        assertThat(first).isSameAs(second);
    }

    @Test
    void statisticsNullBeforeAggregate() {
        final StatisticsProbe probe = new StatisticsProbe("p", ObsUnit.NANOS);
        probe.add(1L);

        assertThat(probe.statistics()).isNull();
    }

    @Test
    void statisticsNonNullAfterAggregate() {
        final StatisticsProbe probe = new StatisticsProbe("p", ObsUnit.NANOS);
        probe.add(1L);
        probe.aggregate();

        assertThat(probe.statistics()).isNotNull();
    }

    @Test
    void addAfterAggregate_throwsIllegalState() {
        final StatisticsProbe probe = new StatisticsProbe("p", ObsUnit.NANOS);
        probe.add(1L);
        probe.aggregate();

        assertThatThrownBy(() -> probe.add(2L)).isInstanceOf(IllegalStateException.class);
    }
}
