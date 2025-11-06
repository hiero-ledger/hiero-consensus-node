// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

public class LongOrDoubleSupplierTest {

    @Test
    void testDoubleSupplierGetters() {
        DoubleSupplier doubleSupplier = () -> 42.5;
        LongOrDoubleSupplier wrapper = new LongOrDoubleSupplier(doubleSupplier);

        assertThat(wrapper.isDoubleSupplier()).isTrue();
        assertThat(wrapper.getDoubleValueSupplier()).isSameAs(doubleSupplier);
        assertThat(wrapper.getDoubleValueSupplier().getAsDouble()).isEqualTo(42.5);

        assertThatThrownBy(wrapper::getLongValueSupplier)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Long value supplier is not set");
    }

    @Test
    void testLongSupplierGetters() {
        LongSupplier longSupplier = () -> 37L;
        LongOrDoubleSupplier wrapper = new LongOrDoubleSupplier(longSupplier);

        assertThat(wrapper.isDoubleSupplier()).isFalse();
        assertThat(wrapper.getLongValueSupplier()).isSameAs(longSupplier);
        assertThat(wrapper.getLongValueSupplier().getAsLong()).isEqualTo(37L);

        assertThatThrownBy(wrapper::getDoubleValueSupplier)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Double value supplier is not set");
    }

    @Test
    void testNullDoubleSupplierThrows() {
        assertThatThrownBy(() -> new LongOrDoubleSupplier((DoubleSupplier) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("valueSupplier cannot be null");
    }

    @Test
    void testNullLongSupplierThrows() {
        assertThatThrownBy(() -> new LongOrDoubleSupplier((LongSupplier) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("valueSupplier cannot be null");
    }
}
