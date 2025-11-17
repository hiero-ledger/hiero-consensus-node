// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class MetricUtilsTest {

    @ParameterizedTest
    @ValueSource(strings = {"valid_name", "ValidName", "name123", "Name_123"})
    void testValidateNameCharactersWithValidNames(String name) {
        String result = MetricUtils.validateNameCharacters(name);
        assertThat(result).isEqualTo(name);
    }

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#invalidNames")
    void testValidateNameCharactersWithInvalidNames(String invalidName) {
        assertThatThrownBy(() -> MetricUtils.validateNameCharacters(invalidName))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testValidateNameCharactersWithNullName() {
        assertThatThrownBy(() -> MetricUtils.validateNameCharacters(null)).isInstanceOf(NullPointerException.class);
    }
}
