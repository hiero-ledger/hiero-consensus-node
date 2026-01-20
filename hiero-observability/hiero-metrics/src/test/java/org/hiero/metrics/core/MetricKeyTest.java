// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hiero.metrics.DoubleCounter;
import org.hiero.metrics.LongCounter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class MetricKeyTest {

    @Test
    void testGetters() {
        MetricKey<LongCounter> key = MetricKey.of("requests", LongCounter.class);

        assertThat(key.name()).isEqualTo("requests");
        assertThat(key.type()).isEqualTo(LongCounter.class);
    }

    @Test
    void testEqualsAndHashCode() {
        MetricKey<LongCounter> key1 = MetricKey.of("requests", LongCounter.class);
        MetricKey<LongCounter> key2 = MetricKey.of("requests", LongCounter.class);

        assertThat(key1).isEqualTo(key2);
        assertThat(key1.hashCode()).isEqualTo(key2.hashCode());
    }

    @Test
    void testNotEqualsWithDifferentName() {
        MetricKey<LongCounter> key1 = MetricKey.of("requests", LongCounter.class);
        MetricKey<LongCounter> key2 = MetricKey.of("errors", LongCounter.class);

        assertThat(key1).isNotEqualTo(key2);
        assertThat(key1.hashCode()).isNotEqualTo(key2.hashCode());
    }

    @Test
    void testNotEqualsWithDifferentType() {
        MetricKey<DoubleCounter> key1 = MetricKey.of("metric", DoubleCounter.class);
        MetricKey<LongCounter> key2 = MetricKey.of("metric", LongCounter.class);

        assertThat(key1).isNotEqualTo(key2);
        assertThat(key1.hashCode()).isNotEqualTo(key2.hashCode());
    }

    @Test
    void testWithCategory() {
        MetricKey<LongCounter> key = MetricKey.of("requests", LongCounter.class);
        MetricKey<LongCounter> categorizedKey = key.addCategory("http");

        assertThat(categorizedKey.name()).isEqualTo("http:requests");
        assertThat(categorizedKey.type()).isEqualTo(LongCounter.class);
    }

    @Test
    void testWithCategoryImmutability() {
        MetricKey<LongCounter> key = MetricKey.of("requests", LongCounter.class);
        MetricKey<LongCounter> categorizedKey = key.addCategory("http");

        assertThat(key).isNotSameAs(categorizedKey);
        assertThat(key.name()).isEqualTo("requests");
        assertThat(key.type()).isEqualTo(LongCounter.class);
    }

    @Test
    void testMultipleCategories() {
        MetricKey<LongCounter> key =
                MetricKey.of("requests", LongCounter.class).addCategory("api").addCategory("http");

        assertThat(key.name()).isEqualTo("http:api:requests");
    }

    @Test
    void testNullNameThrows() {
        assertThatThrownBy(() -> MetricKey.of(null, LongCounter.class)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullTypeThrows() {
        assertThatThrownBy(() -> MetricKey.of("metric", null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#invalidMetricNames")
    void testInvalidNameCharactersThrows(String invalidName) {
        assertThatThrownBy(() -> MetricKey.of(invalidName, LongCounter.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#validMetricNames")
    void testValidNameCharacters(String validName) {
        MetricKey<LongCounter> key = MetricKey.of(validName, LongCounter.class);

        assertThat(key.name()).isEqualTo(validName);
    }

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#invalidMetricNames")
    void testInvalidCategoryCharactersThrows(String invalidCategory) {
        MetricKey<LongCounter> key = MetricKey.of("requests", LongCounter.class);

        assertThatThrownBy(() -> key.addCategory(invalidCategory)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#validMetricNames")
    void testValidCategoryCharacters(String categoryName) {
        String name = "any_name";
        MetricKey<LongCounter> key = MetricKey.of(name, LongCounter.class).addCategory(categoryName);

        assertThat(key.name()).isEqualTo(categoryName + ":" + name);
    }

    @Test
    void testToString() {
        MetricKey<LongCounter> key = MetricKey.of("requests", LongCounter.class);

        assertThat(key.toString()).contains("requests").contains("LongCounter");
    }

    @Test
    void testEqualsWithSameInstance() {
        MetricKey<LongCounter> key = MetricKey.of("requests", LongCounter.class);
        assertThat(key).isEqualTo(key);
    }

    @Test
    void testNotEqualsWithNull() {
        MetricKey<LongCounter> key = MetricKey.of("requests", LongCounter.class);
        assertThat(key).isNotEqualTo(null);
    }

    @Test
    void testNotEqualsWithDifferentClass() {
        MetricKey<LongCounter> key = MetricKey.of("requests", LongCounter.class);
        assertThat(key).isNotEqualTo("requests");
    }
}
