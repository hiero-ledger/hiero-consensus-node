// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

public class MetricMetadataTest {

    @Test
    void testConstructorWithAllNonNullParameters() {
        MetricMetadata metadata =
                new MetricMetadata(MetricType.COUNTER, "test.metric", "Test description", "milliseconds");

        assertThat(metadata.metricType()).isEqualTo(MetricType.COUNTER);
        assertThat(metadata.name()).isEqualTo("test.metric");
        assertThat(metadata.description()).isEqualTo("Test description");
        assertThat(metadata.unit()).isEqualTo("milliseconds");
    }

    @Test
    void testConstructorWithNullDescription() {
        MetricMetadata metadata = new MetricMetadata(MetricType.GAUGE, "test.metric", null, "bytes");

        assertThat(metadata.description()).isNotNull();
        assertThat(metadata.description()).isEmpty();
    }

    @Test
    void testConstructorWithNullUnit() {
        MetricMetadata metadata = new MetricMetadata(MetricType.COUNTER, "test.metric", "Test description", null);

        assertThat(metadata.unit()).isNotNull();
        assertThat(metadata.unit()).isEmpty();
    }

    @Test
    void testConstructorWithNullDescriptionAndUnit() {
        MetricMetadata metadata = new MetricMetadata(MetricType.COUNTER, "test.metric", null, null);

        assertThat(metadata.description()).isNotNull();
        assertThat(metadata.description()).isEmpty();

        assertThat(metadata.unit()).isNotNull();
        assertThat(metadata.unit()).isEmpty();
    }

    @Test
    void testNullMetricTypeThrowsException() {
        assertThatThrownBy(() -> new MetricMetadata(null, "test.metric", "description", "unit"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("metric type must not be null");
    }

    @Test
    void testNullNameThrowsException() {
        assertThatThrownBy(() -> new MetricMetadata(MetricType.COUNTER, null, "description", "unit"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testBlankNameThrowsException() {
        assertThatThrownBy(() -> new MetricMetadata(MetricType.COUNTER, "", "description", "unit"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testWhitespaceNameThrowsException() {
        assertThatThrownBy(() -> new MetricMetadata(MetricType.COUNTER, "   ", "description", "unit"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testRecordEquality() {
        MetricMetadata metadata1 = new MetricMetadata(MetricType.COUNTER, "test.metric", "desc", "unit");
        MetricMetadata metadata2 = new MetricMetadata(MetricType.COUNTER, "test.metric", "desc", "unit");

        assertThat(metadata1).isEqualTo(metadata2);
        assertThat(metadata1.hashCode()).isEqualTo(metadata2.hashCode());
    }

    @Test
    void testRecordInequality() {
        MetricMetadata metadata1 = new MetricMetadata(MetricType.COUNTER, "test.metric", "desc", "unit");
        MetricMetadata metadata2 = new MetricMetadata(MetricType.GAUGE, "test.metric", "desc", "unit");

        assertThat(metadata1).isNotEqualTo(metadata2);
    }
}
