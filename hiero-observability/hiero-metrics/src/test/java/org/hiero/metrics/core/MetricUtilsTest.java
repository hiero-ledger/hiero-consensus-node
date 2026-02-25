// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class MetricUtilsTest {

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#validMetricNames")
    void testValidateMetricNameWithValidCharacters(String name) {
        String result = MetricUtils.validateMetricNameCharacters(name);
        assertThat(result).isEqualTo(name);
    }

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#validUnitNames")
    void testValidateUnitNameWithValidCharacters(String name) {
        String result = MetricUtils.validateUnitNameCharacters(name);
        assertThat(result).isEqualTo(name);
    }

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#validLabelNames")
    void testValidateLabelNameWithValidCharacters(String name) {
        String result = MetricUtils.validateLabelNameCharacters(name);
        assertThat(result).isEqualTo(name);
    }

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#invalidMetricNames")
    void testValidateMetricNameInvalidCharactersThrows(String invalidName) {
        assertThatThrownBy(() -> MetricUtils.validateMetricNameCharacters(invalidName))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#invalidUnitNames")
    void testValidateUnitNameInvalidCharactersThrows(String invalidName) {
        assertThatThrownBy(() -> MetricUtils.validateUnitNameCharacters(invalidName))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#invalidLabelNames")
    void testValidateLabelNameInvalidCharactersThrows(String invalidName) {
        assertThatThrownBy(() -> MetricUtils.validateLabelNameCharacters(invalidName))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testValidateNullMetricNameThrows() {
        assertThatThrownBy(() -> MetricUtils.validateMetricNameCharacters(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void testValidateNullUnitNameThrows() {
        assertThatThrownBy(() -> MetricUtils.validateUnitNameCharacters(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testValidateNullLabelNameThrows() {
        assertThatThrownBy(() -> MetricUtils.validateLabelNameCharacters(null))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, Long.MAX_VALUE, -1L, 0L, 1L, 123456789L})
    void testAsLongSupplier(long initValue) {
        LongSupplier initializer = MetricUtils.asSupplier(initValue);
        assertThat(initializer.getAsLong()).isEqualTo(initValue);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.MIN_VALUE, Double.MAX_VALUE, -1.0, 0.0, 1.0, 12345.6789})
    void testAsDoubleSupplier(double initValue) {
        DoubleSupplier initializer = MetricUtils.asSupplier(initValue);
        assertThat(initializer.getAsDouble()).isEqualTo(initValue);
    }
}
