// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class BasicProbeTest {

    @Test
    void emptyProbe_returnsNil() {
        final BasicProbe probe = new BasicProbe("p", ObsUnit.NANOS);
        assertThat(probe.aggregate()).isSameAs(FixedStatistics.NIL);
    }

    @Test
    void singleValue() {
        final BasicProbe probe = new BasicProbe("p", ObsUnit.NANOS);
        probe.add(100L);
        final Statistics stats = probe.aggregate();

        assertThat(stats.numSamples()).isEqualTo(BigInteger.ONE);
        assertThat(stats.sum()).isEqualTo(BigInteger.valueOf(100));
        assertThat(stats.min()).isEqualTo(BigInteger.valueOf(100));
        assertThat(stats.max()).isEqualTo(BigInteger.valueOf(100));
        assertThat(stats.avg().longValue()).isEqualTo(100);
        assertThat(stats.unit()).isEqualTo(ObsUnit.NANOS);
    }

    @Test
    void multipleValues_computesCorrectAggregates() {
        final BasicProbe probe = new BasicProbe("p", ObsUnit.BYTES);
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
    void minAndMax_trackedCorrectlyForUnsortedInput() {
        final BasicProbe probe = new BasicProbe("p", ObsUnit.BYTES);
        probe.add(50L);
        probe.add(10L);
        probe.add(100L);
        probe.add(25L);
        final Statistics stats = probe.aggregate();

        assertThat(stats.min()).isEqualTo(BigInteger.valueOf(10));
        assertThat(stats.max()).isEqualTo(BigInteger.valueOf(100));
    }

    @Test
    void aggregate_isIdempotent() {
        final BasicProbe probe = new BasicProbe("p", ObsUnit.COUNT);
        probe.add(5L);
        final Statistics first = probe.aggregate();
        final Statistics second = probe.aggregate();

        assertThat(first).isSameAs(second);
    }

    @Test
    void statisticsIsNullBeforeAggregate() {
        final BasicProbe probe = new BasicProbe("p", ObsUnit.NANOS);
        probe.add(1L);

        assertThat(probe.statistics()).isNull();
    }

    @Test
    void statisticsIsNonNullAfterAggregate() {
        final BasicProbe probe = new BasicProbe("p", ObsUnit.NANOS);
        probe.add(1L);
        probe.aggregate();

        assertThat(probe.statistics()).isNotNull();
    }

    @Test
    void addAfterAggregate_throwsIllegalState() {
        final BasicProbe probe = new BasicProbe("p", ObsUnit.NANOS);
        probe.add(1L);
        probe.aggregate();

        assertThatThrownBy(() -> probe.add(2L)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stdDev_isAlwaysZero() {
        final BasicProbe probe = new BasicProbe("p", ObsUnit.NANOS);
        probe.add(1L);
        probe.add(100L);
        probe.add(1000L);

        assertThat(probe.aggregate().stdDev()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void fractionalAverage() {
        final BasicProbe probe = new BasicProbe("p", ObsUnit.NANOS);
        probe.add(1L);
        probe.add(2L);
        final Statistics stats = probe.aggregate();

        assertThat(stats.avg().doubleValue()).isEqualTo(1.5);
    }

    @Test
    void concurrentAdds_allSamplesAccounted() {
        final int threads = 8;
        final int addsPerThread = 1_000;
        final BasicProbe probe = new BasicProbe("p", ObsUnit.COUNT);

        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            final CountDownLatch latch = new CountDownLatch(threads);

            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    for (int j = 0; j < addsPerThread; j++) {
                        probe.add(1L);
                    }
                    latch.countDown();
                });
            }

            latch.await();
            executor.shutdown();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        final Statistics stats = probe.aggregate();
        final long expected = (long) threads * addsPerThread;
        assertThat(stats.numSamples()).isEqualTo(BigInteger.valueOf(expected));
        assertThat(stats.sum()).isEqualTo(BigInteger.valueOf(expected));
    }
}
