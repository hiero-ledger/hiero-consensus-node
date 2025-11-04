// SPDX-License-Identifier: Apache-2.0
package org.hiero.metrics.api.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import org.junit.jupiter.api.Test;

public class ToLongOrDoubleFunctionTest {

    @Test
    public void testDoubleFunctionGetters() {
        ToDoubleFunction<Object> doubleFunction = obj -> 42.5;
        ToLongOrDoubleFunction<Object> wrapper = new ToLongOrDoubleFunction<>(doubleFunction);

        assertThat(wrapper.isToDoubleFunction()).isTrue();
        assertThat(wrapper.getDoubleValueConverter().applyAsDouble(new Object()))
                .isEqualTo(42.5);
        assertThatThrownBy(wrapper::getLongValueConverter)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Long value converter is not set");
    }

    @Test
    public void testLongFunctionGetters() {
        ToLongFunction<Object> longFunction = obj -> 37L;
        ToLongOrDoubleFunction<Object> wrapper = new ToLongOrDoubleFunction<Object>(longFunction);

        assertThat(wrapper.isToDoubleFunction()).isFalse();
        assertThat(wrapper.getLongValueConverter().applyAsLong(new Object())).isEqualTo(37L);

        assertThatThrownBy(wrapper::getDoubleValueConverter)
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Double value converter is not set");
    }

    @Test
    public void testNullDoubleFunctionThrows() {
        assertThatThrownBy(() -> new ToLongOrDoubleFunction<>((ToDoubleFunction<?>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("valueConverter cannot be null");
    }

    @Test
    public void testNullLongSupplierThrows() {
        assertThatThrownBy(() -> new ToLongOrDoubleFunction<>((ToLongFunction<?>) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("valueConverter cannot be null");
    }

    @Test
    public void testDoubleConverterWithNullInput() {
        ToDoubleFunction<String> doubleFunction = Double::parseDouble;
        ToLongOrDoubleFunction<String> wrapper = new ToLongOrDoubleFunction<>(doubleFunction);
        assertThat(wrapper.getDoubleValueConverter().applyAsDouble(null)).isNaN();
    }

    @Test
    public void testLongConverterWithNullInput() {
        ToLongFunction<String> longFunction = Long::parseLong;
        ToLongOrDoubleFunction<String> wrapper = new ToLongOrDoubleFunction<>(longFunction);
        assertThat(wrapper.getLongValueConverter().applyAsLong(null)).isEqualTo(0L);
    }
}
