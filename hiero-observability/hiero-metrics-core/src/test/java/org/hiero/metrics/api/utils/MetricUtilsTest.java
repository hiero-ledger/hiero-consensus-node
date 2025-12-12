// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
}
