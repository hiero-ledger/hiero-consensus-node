// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;

import org.hiero.metrics.api.datapoint.DoubleGaugeCompositeDataPoint;
import org.junit.jupiter.api.Test;

public class DoubleGaugeCompositeTest {

    @Test
    public void testFailBuildWhenNoStatsDefined() {
        assertThatThrownBy(() -> DoubleGaugeComposite.builder(DoubleGaugeComposite.key("noStats"))
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("At least one stat must be defined");
    }

    @Test
    public void testFailBuildWhenDuplicateStatsDefined() {
        assertThatThrownBy(() -> DoubleGaugeComposite.builder(DoubleGaugeComposite.key("multipleDoubleStats"))
                        .withAccumulatorStat("stat1", Double::max, 0.0)
                        .withAccumulatorStat("stat1", Double::min, 0.0)
                        .build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Stat names must be unique");
    }

    @Test
    public void testSingleSumStat() {
        DoubleGaugeCompositeDataPoint dataPoint = DoubleGaugeComposite.builder(
                        DoubleGaugeComposite.key("singleSumStat"))
                .withSumStat()
                .build()
                .getOrCreateNotLabeled();

        assertThat(dataPoint.size()).isEqualTo(1);

        // given
        dataPoint.update(1.3);
        // than
        assertThat(dataPoint.get(0).getAsDouble()).isEqualTo(1.3);

        // given
        dataPoint.update(1.7);
        // than
        assertThat(dataPoint.get(0).getAsDouble()).isEqualTo(3.0);
    }

    // TODO other stats tests

    @Test
    public void testMultipleStats() {
        DoubleGaugeCompositeDataPoint dataPoint = DoubleGaugeComposite.builder(
                        DoubleGaugeComposite.key("multipleDoubleStats"))
                .withSumStat()
                .withAccumulatorStat("sumPlusOne", (v1, v2) -> v1 + v2 + 1)
                .build()
                .getOrCreateNotLabeled();

        assertThat(dataPoint.size()).isEqualTo(2);

        // given
        dataPoint.update(1.3);

        // than
        assertThat(dataPoint.get(0).getAsDouble()).isEqualTo(1.3);
        assertThat(dataPoint.get(1).getAsDouble()).isEqualTo(2.3);

        // given
        dataPoint.update(1.7);

        // than
        assertThat(dataPoint.get(0).getAsDouble()).isEqualTo(3.0);
        assertThat(dataPoint.get(1).getAsDouble()).isEqualTo(5.0);
    }
}
