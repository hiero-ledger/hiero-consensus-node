// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.extensions.test.fixtures.TestConfigBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class UtilsTest {
    @Test
    @DisplayName("networkProperties() returns expected properties")
    void networkProperties() {
        final var config = new TestConfigBuilder(false)
                .withConfigDataType(NoNetworkAnnotatedConfig.class)
                .withConfigDataType(MixedAnnotatedConfig.class)
                .getOrCreateConfig();

        final var propertyNames = Utils.networkProperties(config);
        assertThat(propertyNames).containsOnlyKeys("networkProperty");
    }

    @Test
    @DisplayName("allProperties() keeps a property whose value is null")
    void allPropertiesWithNullValue() {
        final var config = new TestConfigBuilder(false)
                .withConfigDataType(NullableConfig.class)
                .getOrCreateConfig();

        assertThatCode(() -> Utils.allProperties(config)).doesNotThrowAnyException();
        assertThat(Utils.allProperties(config)).containsEntry("nullable.value", null);
    }

    @ConfigData("nullable")
    public record NullableConfig(
            @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
            String value) {}
}
