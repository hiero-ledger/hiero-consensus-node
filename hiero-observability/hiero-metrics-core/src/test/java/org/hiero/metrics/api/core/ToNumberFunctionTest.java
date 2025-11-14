// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Test;

public class ToNumberFunctionTest {

    @Test
    void testDoubleFunctionGetters() {
        ToDoubleFunction<Object> doubleFunction = obj -> 42.5;
        ToNumberFunction<Object> wrapper = new ToNumberFunction<>(doubleFunction);

        assertThat(wrapper.isFloatingPointFunction()).isTrue();
        assertThat(wrapper.getToDoubleFunction().applyAsDouble(new Object())).isEqualTo(42.5);
        assertThatThrownBy(wrapper::getToLongFunction)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Long value converter is not set");
    }

    @Test
    void testLongFunctionGetters() {
        ToLongFunction<Object> longFunction = obj -> 37L;
        ToNumberFunction<Object> wrapper = new ToNumberFunction<Object>(longFunction);

        assertThat(wrapper.isFloatingPointFunction()).isFalse();
        assertThat(wrapper.getToLongFunction().applyAsLong(new Object())).isEqualTo(37L);

        assertThatThrownBy(wrapper::getToDoubleFunction)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Double value converter is not set");
    }

    @Test
    void testNullDoubleFunctionThrows() {
        assertThatThrownBy(() -> new ToNumberFunction<>((ToDoubleFunction<?>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("valueConverter cannot be null");
    }

    @Test
    void testNullLongSupplierThrows() {
        assertThatThrownBy(() -> new ToNumberFunction<>((ToLongFunction<?>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("valueConverter cannot be null");
    }

    @Test
    void testDoubleConverterWithNullInput() {
        ToDoubleFunction<String> doubleFunction = Double::parseDouble;
        ToNumberFunction<String> wrapper = new ToNumberFunction<>(doubleFunction);
        assertThat(wrapper.getToDoubleFunction().applyAsDouble(null)).isNaN();
    }

    @Test
    void testLongConverterWithNullInput() {
        ToLongFunction<String> longFunction = Long::parseLong;
        ToNumberFunction<String> wrapper = new ToNumberFunction<>(longFunction);
        assertThat(wrapper.getToLongFunction().applyAsLong(null)).isEqualTo(0L);
    }
}
