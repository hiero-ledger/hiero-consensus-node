// SPDX-License-Identifier: Apache-2.0
package com.swirlds.metrics.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

abstract class BaseConfigTest<C extends MetricConfig<?, C>> {

    protected static final String CATEGORY = "CaTeGoRy";
    protected static final String NAME = "NaMe";
    protected static final String DESCRIPTION = "DeScRiPtIoN";
    protected static final String UNIT = "UnIt";
    protected static final String FORMAT = "FoRmAt";

    protected abstract C create(String category, String name);

    protected final C createBase() {
        return create(CATEGORY, NAME);
    }

    protected final C createFull() {
        return create(CATEGORY, NAME)
                .withDescription(DESCRIPTION)
                .withUnit(UNIT)
                .withFormat(FORMAT);
    }

    @Test
    @DisplayName("Constructor fails with illegal basic parameters")
    void testBaseConstructorWithIllegalParameter() {
        assertThatThrownBy(() -> create(null, NAME)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> create("", NAME)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(" \t\n", NAME)).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> create(CATEGORY, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> create(CATEGORY, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> create(CATEGORY, " \t\n")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Constructor initializes basic fields")
    void testConstructorBaseFields() {
        // when
        final C config = create(CATEGORY, NAME);

        // then
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getName()).isEqualTo(NAME);
        assertThat(config.getDescription()).isEqualTo(NAME);
        assertThat(config.getUnit()).isEmpty();
    }

    @Test
    @DisplayName("Basic setters fail with illegal parameters")
    void testBaseSettersWithIllegalParameters() {
        // given
        final C config = create(CATEGORY, NAME);

        // then
        assertThatThrownBy(() -> config.withDescription(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> config.withDescription("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.withDescription(" \t\n")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.withDescription(DESCRIPTION.repeat(50)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> config.withUnit(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> config.withUnit("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.withUnit("\t\n")).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> config.withFormat(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> config.withFormat("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config.withFormat("\t\n")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Basic setters store values")
    void testBaseSetters() {
        // when
        C config = createFull();

        // then
        assertThat(config.getCategory()).isEqualTo(CATEGORY);
        assertThat(config.getName()).isEqualTo(NAME);
        assertThat(config.getDescription()).isEqualTo(DESCRIPTION);
        assertThat(config.getUnit()).isEqualTo(UNIT);
        assertThat(config.getFormat()).isEqualTo(FORMAT);
    }

    @Test
    @DisplayName("toString contains basic values")
    void testToStringBase() {
        // when
        final C config = createFull();

        // then
        assertThat(config.toString())
                .contains(
                        CATEGORY,
                        NAME,
                        DESCRIPTION,
                        UNIT,
                        FORMAT,
                        config.getResultClass().getSimpleName());
    }
}
