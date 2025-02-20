// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.config.types.LongPair;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class LongPairConverterTest {

    @Test
    void testNullValue() {
        // given
        final LongPairConverter converter = new LongPairConverter();
        final String value = null;

        // then
        assertThatThrownBy(() -> converter.convert(value)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testValidValue() {
        // given
        final LongPairConverter converter = new LongPairConverter();
        final String value = "1-2";

        // when
        final var result = converter.convert(value);

        // then
        assertThat(result.left()).isEqualTo(1L);
        assertThat(result.right()).isEqualTo(2L);
    }

    @Test
    void testInvalidValueMultipleDashes() {
        // given
        final LongPairConverter converter = new LongPairConverter();
        final String value = "1-2-3;";

        // then
        assertThatThrownBy(() -> converter.convert(value)).isInstanceOf(NumberFormatException.class);
    }

    @Test
    void testInvalidValueNoDashes() {
        // given
        final LongPairConverter converter = new LongPairConverter();
        final String value = "1";

        // then
        assertThatThrownBy(() -> converter.convert(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testIncludedInConfig() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withConverter(LongPair.class, new LongPairConverter())
                .withSource(new SimpleConfigSource("testProperty", "1-2"))
                .build();

        // when
        final LongPair value = configuration.getValue("testProperty", LongPair.class);

        // then
        assertThat(value).isNotNull();
        assertThat(value.left()).isEqualTo(1L);
        assertThat(value.right()).isEqualTo(2L);
    }

    @Test
    void testIncludedInListInConfig() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withConverter(LongPair.class, new LongPairConverter())
                .withSource(new SimpleConfigSource("testProperty", "1-2"))
                .build();

        // when
        final List<LongPair> values = configuration.getValues("testProperty", LongPair.class);

        // then
        assertThat(values).hasSize(1);
        assertThat(values.get(0).left()).isEqualTo(1L);
        assertThat(values.get(0).right()).isEqualTo(2L);
    }

    @Test
    void testMultipleIncludedInListInConfig() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withConverter(LongPair.class, new LongPairConverter())
                .withSource(new SimpleConfigSource("testProperty", "1-2,3-4,5-6"))
                .build();

        // when
        final List<LongPair> values = configuration.getValues("testProperty", LongPair.class);

        // then
        assertThat(values).isNotNull().hasSize(3);
        assertThat(values.get(0).left()).isEqualTo(1L);
        assertThat(values.get(0).right()).isEqualTo(2L);
        assertThat(values.get(1).left()).isEqualTo(3L);
        assertThat(values.get(1).right()).isEqualTo(4L);
        assertThat(values.get(2).left()).isEqualTo(5L);
        assertThat(values.get(2).right()).isEqualTo(6L);
    }
}
