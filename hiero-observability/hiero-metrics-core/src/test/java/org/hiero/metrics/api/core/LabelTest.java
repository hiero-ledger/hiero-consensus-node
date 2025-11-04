// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

public class LabelTest {

    @Test
    public void testValidLabel() {
        Label label = new Label("method", "GET");

        assertThat(label.name()).isEqualTo("method");
        assertThat(label.value()).isEqualTo("GET");
    }

    @Test
    public void testEqualsAndHashCode() {
        Label a = new Label("status", "200");
        Label b = new Label("status", "200");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
        assertThat(a.compareTo(b)).isEqualTo(0);
    }

    @Test
    public void testCompareToOrdering() {
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
    public void testBlankNameThrows() {
        assertThatThrownBy(() -> new Label("", "value")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testBlankValueThrows() {
        assertThatThrownBy(() -> new Label("name", "")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testInvalidNameCharactersThrows() {
        // assume names with invalid characters (e.g. '$') are rejected by MetricUtils.validateNameCharacters
        assertThatThrownBy(() -> new Label("inva$lid", "value")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testNullNameThrows() {
        assertThatThrownBy(() -> new Label(null, "value")).isInstanceOf(NullPointerException.class);
    }

    @Test
    public void testNullValueThrows() {
        assertThatThrownBy(() -> new Label("name", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    public void testToString() {
        Label label = new Label("method", "GET");

        assertThat(label.toString()).contains("method").contains("GET");
    }

    @Test
    public void testEqualsWithSameInstance() {
        Label label = new Label("method", "GET");
        assertThat(label).isEqualTo(label);
    }

    @Test
    public void testNotEqualsWithNull() {
        Label label = new Label("method", "GET");
        assertThat(label).isNotEqualTo(null);
    }

    @Test
    public void testNotEqualsWithDifferentClass() {
        Label label = new Label("method", "GET");
        assertThat(label).isNotEqualTo("requests");
    }
}
