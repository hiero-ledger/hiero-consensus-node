// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

public class NumberSupplierTest {

    @Test
    void testDoubleSupplierGetters() {
        DoubleSupplier doubleSupplier = () -> 42.5;
        NumberSupplier wrapper = new NumberSupplier(doubleSupplier);

        assertThat(wrapper.isFloatingSupplier()).isTrue();
        assertThat(wrapper.getDoubleSupplier()).isSameAs(doubleSupplier);
        assertThat(wrapper.getDoubleSupplier().getAsDouble()).isEqualTo(42.5);

        assertThatThrownBy(wrapper::getLongSupplier)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Long value supplier is not set");
    }

    @Test
    void testLongSupplierGetters() {
        LongSupplier longSupplier = () -> 37L;
        NumberSupplier wrapper = new NumberSupplier(longSupplier);

        assertThat(wrapper.isFloatingSupplier()).isFalse();
        assertThat(wrapper.getLongSupplier()).isSameAs(longSupplier);
        assertThat(wrapper.getLongSupplier().getAsLong()).isEqualTo(37L);

        assertThatThrownBy(wrapper::getDoubleSupplier)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Double value supplier is not set");
    }

    @Test
    void testNullDoubleSupplierThrows() {
        assertThatThrownBy(() -> new NumberSupplier((DoubleSupplier) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value supplier cannot be null");
    }

    @Test
    void testNullLongSupplierThrows() {
        assertThatThrownBy(() -> new NumberSupplier((LongSupplier) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("value supplier cannot be null");
    }
}
