// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.swirlds.common.metrics.statistics.StatsBuffered;
import com.swirlds.metrics.api.FloatFormats;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StatEntryConfigTest {

    private static final String DEFAULT_FORMAT = FloatFormats.FORMAT_11_3;

    private static final String CATEGORY = "CaTeGoRy";
    private static final String NAME = "NaMe";
    private static final String DESCRIPTION = "DeScRiPtIoN";
    private static final String UNIT = "UnIt";
    private static final String FORMAT = "FoRmAt";

    @SuppressWarnings("unchecked")
    @Test
    void testConstructor() {
        // given
        final Supplier<Object> getter = mock(Supplier.class);

        // when
        final StatEntry.Config config = new StatEntry.Config(CATEGORY, NAME, Object.class, getter);

        // then
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getName()).isEqualTo(NAME);
        assertThat(config.getDescription()).isEqualTo(NAME);
        assertThat(config.getUnit()).isEmpty();
        assertThat(config.getFormat()).isEqualTo(DEFAULT_FORMAT);
        assertThat(config.getType()).isEqualTo(Object.class);
        assertThat(config.getBuffered()).isNull();
        assertThat(config.getInit()).isNull();
        assertThat(config.getReset()).isNull();
        assertThat(config.getStatsStringSupplier()).isEqualTo(getter);
        assertThat(config.getResetStatsStringSupplier()).isEqualTo(getter);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("Constructor should throw IAE when passing illegal parameters")
    void testConstructorWithIllegalParameter() {
        // given
        final Supplier<Object> getter = mock(Supplier.class);

        // when
        assertThatThrownBy(() -> new StatEntry.Config(null, NAME, Object.class, getter))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StatEntry.Config("", NAME, Object.class, getter))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StatEntry.Config(" \t\n", NAME, Object.class, getter))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new StatEntry.Config(CATEGORY, null, Object.class, getter))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StatEntry.Config(CATEGORY, "", Object.class, getter))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StatEntry.Config(CATEGORY, " \t\n", Object.class, getter))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new StatEntry.Config(CATEGORY, NAME, null, getter))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new StatEntry.Config(CATEGORY, NAME, Object.class, null))
                .isInstanceOf(NullPointerException.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void testSetters() {
        // given
        final StatsBuffered buffered = mock(StatsBuffered.class);
        final Function<Double, StatsBuffered> init = mock(Function.class);
        final Consumer<Double> reset = mock(Consumer.class);
        final Supplier<Object> getter = mock(Supplier.class);
        final Supplier<Object> getAndReset = mock(Supplier.class);
        final StatEntry.Config config = new StatEntry.Config(CATEGORY, NAME, Object.class, getter);

        // when
        final StatEntry.Config result = config.withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT)
                .withBuffered(buffered)
                .withInit(init)
                .withReset(reset)
                .withResetStatsStringSupplier(getAndReset);

        // then
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getName()).isEqualTo(NAME);
        assertThat(config.getDescription()).isEqualTo(NAME);
        assertThat(config.getUnit()).isEmpty();
        assertThat(config.getFormat()).isEqualTo(DEFAULT_FORMAT);
        assertThat(config.getType()).isEqualTo(Object.class);
        assertThat(config.getBuffered()).isNull();
        assertThat(config.getInit()).isNull();
        assertThat(config.getReset()).isNull();
        assertThat(config.getStatsStringSupplier()).isEqualTo(getter);
        assertThat(config.getResetStatsStringSupplier()).isEqualTo(getter);

        assertThat(result.getCategory()).isEqualTo(CATEGORY);
        assertThat(result.getName()).isEqualTo(NAME);
        assertThat(result.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(result.getUnit()).isEqualTo(UNIT);
        assertThat(result.getFormat()).isEqualTo(FORMAT);
        assertThat(result.getType()).isEqualTo(Object.class);
        assertThat(result.getBuffered()).isEqualTo(buffered);
        assertThat(result.getInit()).isEqualTo(init);
        assertThat(result.getReset()).isEqualTo(reset);
        assertThat(result.getStatsStringSupplier()).isEqualTo(getter);
        assertThat(result.getResetStatsStringSupplier()).isEqualTo(getAndReset);
    }

    @SuppressWarnings("unchecked")
    @Test
    void testSettersWithIllegalParameters() {
        // given
        final Supplier<Object> getter = mock(Supplier.class);
        final StatEntry.Config config = new StatEntry.Config(CATEGORY, NAME, Object.class, getter);
        final String longDescription = DESCRIPTION.repeat(50);

        // then
        assertThatThrownBy(() -> config.withDescription(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> config.withDescription("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.withDescription(" \t\n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.withDescription(longDescription)).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> config.withUnit(null)).isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> config.withFormat(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> config.withFormat("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.withFormat(" \t\n")).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> config.withResetStatsStringSupplier(null)).isInstanceOf(NullPointerException.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void testToString() {
        // given
        final Supplier<Object> getter = mock(Supplier.class);
        final StatEntry.Config config = new StatEntry.Config(CATEGORY, NAME, Object.class, getter)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT);

        // then
        assertThat(config.toString()).contains(CATEGORY, NAME, DESCRIPTION, UNIT, FORMAT, "java.lang.Object");
    }
}
