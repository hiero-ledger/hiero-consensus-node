// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import com.hedera.node.app.hapi.utils.sysfiles.domain.throttling.ScaleFactor;
import com.hedera.node.config.types.EntityScaleFactors;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class EntityScaleFactorsConverterTest {

    @Test
    void testNullValue() {
        // given
        final EntityScaleFactorsConverter converter = new EntityScaleFactorsConverter();

        // then
        Assertions.assertThatThrownBy(() -> converter.convert(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void testValidValue() {
        // given
        final EntityScaleFactorsConverter converter = new EntityScaleFactorsConverter();
        final String input = "DEFAULT(90,10:1,95,25:1,99,100:1)";

        // when
        final EntityScaleFactors entityScaleFactors = converter.convert(input);

        // then
        Assertions.assertThat(entityScaleFactors).isNotNull();
        Assertions.assertThat(entityScaleFactors.typeScaleFactors()).isEmpty();
        Assertions.assertThat(entityScaleFactors.defaultScaleFactors()).isNotNull();
        Assertions.assertThat(entityScaleFactors.defaultScaleFactors().usagePercentTriggers())
                .containsExactly(90, 95, 99);
        Assertions.assertThat(entityScaleFactors.defaultScaleFactors().scaleFactors())
                .containsExactly(ScaleFactor.from("10:1"), ScaleFactor.from("25:1"), ScaleFactor.from("100:1"));
    }

    @Test
    void testEmptyValue() {
        // given
        final EntityScaleFactorsConverter converter = new EntityScaleFactorsConverter();
        final String input = "";

        // when
        final EntityScaleFactors entityScaleFactors = converter.convert(input);

        // then
        Assertions.assertThat(entityScaleFactors).isNotNull();
        Assertions.assertThat(entityScaleFactors.typeScaleFactors()).isEmpty();
        Assertions.assertThat(entityScaleFactors.defaultScaleFactors()).isNotNull();
        Assertions.assertThat(entityScaleFactors.defaultScaleFactors().usagePercentTriggers())
                .containsExactly(0);
        Assertions.assertThat(entityScaleFactors.defaultScaleFactors().scaleFactors())
                .containsExactly(ScaleFactor.from("1:1"));
    }
}
