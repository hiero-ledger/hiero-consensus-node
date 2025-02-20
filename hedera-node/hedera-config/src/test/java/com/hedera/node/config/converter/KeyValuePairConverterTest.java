// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.config.types.KeyValuePair;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import java.util.List;
import org.junit.jupiter.api.Test;

class KeyValuePairConverterTest {

    @Test
    void testNullValue() {
        // given
        final KeyValuePairConverter converter = new KeyValuePairConverter();
        final String value = null;

        // then
        assertThatThrownBy(() -> converter.convert(value)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testValidValue() {
        // given
        final KeyValuePairConverter converter = new KeyValuePairConverter();
        final String value = "key=value";

        // when
        final var result = converter.convert(value);

        // then
        assertThat(result.key()).isEqualTo("key");
        assertThat(result.value()).isEqualTo("value");
    }

    @Test
    void testValidValueMultipleSemicolon() {
        // given
        final KeyValuePairConverter converter = new KeyValuePairConverter();
        final String value = "key=value=stillValue;";

        // when
        final var result = converter.convert(value);

        // then
        assertThat(result.key()).isEqualTo("key");
        assertThat(result.value()).isEqualTo("value=stillValue;");
    }

    @Test
    void testInvalidValueNoSemicolon() {
        // given
        final KeyValuePairConverter converter = new KeyValuePairConverter();
        final String value = "key";

        // then
        assertThatThrownBy(() -> converter.convert(value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void testIncludedInConfig() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withConverter(KeyValuePair.class, new KeyValuePairConverter())
                .withSource(new SimpleConfigSource("testProperty", "key=value"))
                .build();

        // when
        final KeyValuePair value = configuration.getValue("testProperty", KeyValuePair.class);

        // then
        assertThat(value).isNotNull();
        assertThat(value.key()).isEqualTo("key");
        assertThat(value.value()).isEqualTo("value");
    }

    @Test
    void testIncludedInListInConfig() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withConverter(KeyValuePair.class, new KeyValuePairConverter())
                .withSource(new SimpleConfigSource("testProperty", "key=value"))
                .build();

        // when
        final List<KeyValuePair> values = configuration.getValues("testProperty", KeyValuePair.class);

        // then
        assertThat(values).isNotNull().hasSize(1);
        assertThat(values.get(0).key()).isEqualTo("key");
        assertThat(values.get(0).value()).isEqualTo("value");
    }

    @Test
    void testMultipleIncludedInListInConfig() {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withConverter(KeyValuePair.class, new KeyValuePairConverter())
                .withSource(new SimpleConfigSource("testProperty", "key1=value1,key2=value2,key3=value3"))
                .build();

        // when
        final List<KeyValuePair> values = configuration.getValues("testProperty", KeyValuePair.class);

        // then
        assertThat(values).isNotNull().hasSize(3);
        assertThat(values.get(0).key()).isEqualTo("key1");
        assertThat(values.get(0).value()).isEqualTo("value1");
        assertThat(values.get(1).key()).isEqualTo("key2");
        assertThat(values.get(1).value()).isEqualTo("value2");
        assertThat(values.get(2).key()).isEqualTo("key3");
        assertThat(values.get(2).value()).isEqualTo("value3");
    }
}
