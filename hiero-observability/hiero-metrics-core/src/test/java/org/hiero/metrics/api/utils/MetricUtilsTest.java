// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.hiero.metrics.api.core.Label;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class MetricUtilsTest {

    @Test
    void testAsListWithNullLabels() {
        List<Label> result = MetricUtils.asList((Label[]) null);
        assertThat(result).isEmpty();
    }

    @Test
    void testAsListWithEmptyLabels() {
        List<Label> result = MetricUtils.asList();
        assertThat(result).isEmpty();
    }

    @Test
    void testAsListWithSingleLabel() {
        Label label = new Label("key", "value");
        List<Label> result = MetricUtils.asList(label);
        assertThat(result).containsExactly(label);
    }

    @Test
    void testAsListWithMultipleLabels() {
        Label label1 = new Label("key1", "value1");
        Label label2 = new Label("key2", "value2");
        List<Label> result = MetricUtils.asList(label1, label2);
        assertThat(result).containsExactly(label1, label2);
    }

    @Test
    void testAsListWithDuplicateLabels() {
        Label label1 = new Label("key", "value1");
        Label label2 = new Label("key", "value2");
        assertThatThrownBy(() -> MetricUtils.asList(label1, label2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate label name: key");
    }

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
