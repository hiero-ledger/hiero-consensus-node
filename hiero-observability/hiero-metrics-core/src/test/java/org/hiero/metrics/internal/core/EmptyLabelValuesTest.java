// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class EmptyLabelValuesTest {

    @Test
    void testSingleton() {
        LabelValues instance1 = LabelValues.empty();
        LabelValues instance2 = LabelValues.empty();

        assertThat(instance1).isNotNull();
        assertThat(instance2).isNotNull();

        assertThat(instance1).isEqualTo(instance2);
        assertThat(instance1.hashCode()).isEqualTo(instance2.hashCode());

        assertThat(instance1).isSameAs(instance2);
    }

    @Test
    void testEqualsAndHashCode() {
        LabelValues empty = LabelValues.empty();
        LabelValues notEmpty = new LabelNamesAndValues("name", "value");

        assertThat(empty).isNotEqualTo(notEmpty);
        assertThat(empty.hashCode()).isNotEqualTo(notEmpty.hashCode());
    }

    @Test
    void testSize() {
        assertThat(LabelValues.empty().size()).isEqualTo(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    void testGetThrowsIndexOutOfBoundsException(int index) {
        assertThatThrownBy(() -> LabelValues.empty().get(index))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessage("Label values is empty");
    }

    @Test
    void testToString() {
        assertThat(LabelValues.empty().toString()).isEqualTo("LabelsValues[]");
    }
}
