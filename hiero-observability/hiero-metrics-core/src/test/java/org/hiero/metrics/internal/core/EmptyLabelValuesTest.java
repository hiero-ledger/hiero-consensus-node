// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.internal.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

public class EmptyLabelValuesTest {

    @Test
    public void testSingleton() {
        LabelValues instance1 = LabelValues.empty();
        EmptyLabelValues instance2 = EmptyLabelValues.INSTANCE;

        assertThat(instance1).isNotNull();
        assertThat(instance2).isNotNull();

        assertThat(instance1).isEqualTo(instance2);
        assertThat(instance1.hashCode()).isEqualTo(instance2.hashCode());

        assertThat(instance1).isSameAs(instance2);
    }

    @Test
    public void testSize() {
        assertThat(EmptyLabelValues.INSTANCE.size()).isEqualTo(0);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1})
    public void testGetThrowsIndexOutOfBoundsException(int index) {
        assertThatThrownBy(() -> EmptyLabelValues.INSTANCE.get(index))
                .isInstanceOf(IndexOutOfBoundsException.class)
                .hasMessage("Label values is empty");
    }
}
