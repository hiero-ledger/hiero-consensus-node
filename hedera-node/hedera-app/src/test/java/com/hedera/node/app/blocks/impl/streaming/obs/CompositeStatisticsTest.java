// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;

class CompositeStatisticsTest {

    @Test
    void emptyComposite_returnsNil() {
        final CompositeStatistics composite = new CompositeStatistics(ObsUnit.NANOS);

        assertThat(composite.numSamples()).isEqualTo(BigInteger.ZERO);
        assertThat(composite.sum()).isEqualTo(BigInteger.ZERO);
    }

    @Test
    void nilComponent_doesNotCauseDivisionByZero() {
        final CompositeStatistics composite = new CompositeStatistics(ObsUnit.NANOS);
        composite.add(FixedStatistics.nil(ObsUnit.NANOS));

        assertThat(composite.numSamples()).isEqualTo(BigInteger.ZERO);
    }

    @Test
    void singleComponent_reflectsItDirectly() {
        final FixedStatistics component = new FixedStatistics(
                ObsUnit.NANOS,
                BigInteger.valueOf(3),
                BigInteger.valueOf(60),
                BigInteger.valueOf(10),
                BigInteger.valueOf(30),
                BigDecimal.valueOf(20),
                BigDecimal.ZERO);

        final CompositeStatistics composite = new CompositeStatistics(ObsUnit.NANOS);
        composite.add(component);

        assertThat(composite.numSamples()).isEqualTo(BigInteger.valueOf(3));
        assertThat(composite.sum()).isEqualTo(BigInteger.valueOf(60));
        assertThat(composite.min()).isEqualTo(BigInteger.valueOf(10));
        assertThat(composite.max()).isEqualTo(BigInteger.valueOf(30));
        assertThat(composite.avg().doubleValue()).isEqualTo(20.0);
    }

    @Test
    void twoComponents_combinesMinMaxSumCount() {
        // Group 1: values [2, 4], avg=3, stdDev=1
        final FixedStatistics g1 = new FixedStatistics(
                ObsUnit.NANOS,
                BigInteger.valueOf(2),
                BigInteger.valueOf(6),
                BigInteger.valueOf(2),
                BigInteger.valueOf(4),
                BigDecimal.valueOf(3),
                BigDecimal.ONE);

        // Group 2: values [6, 8], avg=7, stdDev=1
        final FixedStatistics g2 = new FixedStatistics(
                ObsUnit.NANOS,
                BigInteger.valueOf(2),
                BigInteger.valueOf(14),
                BigInteger.valueOf(6),
                BigInteger.valueOf(8),
                BigDecimal.valueOf(7),
                BigDecimal.ONE);

        final CompositeStatistics composite = new CompositeStatistics(ObsUnit.NANOS);
        composite.add(g1);
        composite.add(g2);

        assertThat(composite.numSamples()).isEqualTo(BigInteger.valueOf(4));
        assertThat(composite.sum()).isEqualTo(BigInteger.valueOf(20));
        assertThat(composite.min()).isEqualTo(BigInteger.valueOf(2));
        assertThat(composite.max()).isEqualTo(BigInteger.valueOf(8));
        // combined avg = 20/4 = 5
        assertThat(composite.avg().doubleValue()).isEqualTo(5.0);
    }

    @Test
    void twoComponents_combinedStdDev() {
        // Group 1: n=2, avg=3, stdDev=1; Group 2: n=2, avg=7, stdDev=1
        // Combined avg = 5
        // Combined variance = (2*(1^2 + (3-5)^2) + 2*(1^2 + (7-5)^2)) / 4
        //                   = (2*(1+4) + 2*(1+4)) / 4 = 20/4 = 5
        // Combined stdDev = sqrt(5) ≈ 2.2360679...
        final FixedStatistics g1 = new FixedStatistics(
                ObsUnit.NANOS,
                BigInteger.valueOf(2),
                BigInteger.valueOf(6),
                BigInteger.valueOf(2),
                BigInteger.valueOf(4),
                BigDecimal.valueOf(3),
                BigDecimal.ONE);
        final FixedStatistics g2 = new FixedStatistics(
                ObsUnit.NANOS,
                BigInteger.valueOf(2),
                BigInteger.valueOf(14),
                BigInteger.valueOf(6),
                BigInteger.valueOf(8),
                BigDecimal.valueOf(7),
                BigDecimal.ONE);

        final CompositeStatistics composite = new CompositeStatistics(ObsUnit.NANOS);
        composite.add(g1);
        composite.add(g2);

        assertThat(composite.stdDev().doubleValue()).isCloseTo(Math.sqrt(5), within(0.0001));
    }

    @Test
    void addUpdatesCompositeIncrementally() {
        final CompositeStatistics composite = new CompositeStatistics(ObsUnit.COUNT);

        final FixedStatistics s1 = new FixedStatistics(
                ObsUnit.COUNT,
                BigInteger.ONE,
                BigInteger.TEN,
                BigInteger.TEN,
                BigInteger.TEN,
                BigDecimal.TEN,
                BigDecimal.ZERO);
        composite.add(s1);
        assertThat(composite.numSamples()).isEqualTo(BigInteger.ONE);

        final FixedStatistics s2 = new FixedStatistics(
                ObsUnit.COUNT,
                BigInteger.TWO,
                BigInteger.valueOf(20),
                BigInteger.valueOf(5),
                BigInteger.valueOf(15),
                BigDecimal.valueOf(10),
                BigDecimal.ZERO);
        composite.add(s2);
        assertThat(composite.numSamples()).isEqualTo(BigInteger.valueOf(3));
    }
}
