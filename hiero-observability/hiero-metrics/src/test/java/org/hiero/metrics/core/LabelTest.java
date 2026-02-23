// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

public class LabelTest {

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#validLabelNames")
    void testValidLabel(String validName) {
        Label label = new Label(validName, "GET");

        assertThat(label.name()).isEqualTo(validName);
        assertThat(label.value()).isEqualTo("GET");
    }

    @Test
    void testEqualsAndHashCode() {
        Label a = new Label("status", "200");
        Label b = new Label("status", "200");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.compareTo(b)).isEqualTo(0);
    }

    @Test
    void testCompareToOrdering() {
        Label a1 = new Label("a", "1");
        Label a2 = new Label("a", "2");
        Label b1 = new Label("b", "1");

        // same name, compare by value
        assertThat(a1.compareTo(a2)).isLessThan(0);
        assertThat(a2.compareTo(a1)).isGreaterThan(0);

        // different names
        assertThat(a1.compareTo(b1)).isLessThan(0);
        assertThat(b1.compareTo(a1)).isGreaterThan(0);
    }

    @Test
    void testBlankNameThrows() {
        assertThatThrownBy(() -> new Label("", "value")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testBlankValueThrows() {
        assertThatThrownBy(() -> new Label("name", "")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("org.hiero.metrics.TestUtils#invalidLabelNames")
    void testInvalidNameCharactersThrows(String invalidName) {
        assertThatThrownBy(() -> new Label(invalidName, "value")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testNullNameThrows() {
        assertThatThrownBy(() -> new Label(null, "value")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testNullValueThrows() {
        assertThatThrownBy(() -> new Label("name", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testToString() {
        Label label = new Label("method", "GET");

        assertThat(label.toString()).contains("method").contains("GET");
    }

    @Test
    void testEqualsWithSameInstance() {
        Label label = new Label("method", "GET");
        assertThat(label).isEqualTo(label);
    }

    @Test
    void testNotEqualsWithNull() {
        Label label = new Label("method", "GET");
        assertThat(label).isNotEqualTo(null);
    }

    @Test
    void testNotEqualsWithDifferentClass() {
        Label label = new Label("method", "GET");
        assertThat(label).isNotEqualTo("requests");
    }
}
