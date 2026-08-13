// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigProperty;
import com.swirlds.config.api.NestedConfig;
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

    @Test
    @DisplayName("networkProperties() reports the annotated properties of a nested config data object")
    void networkPropertiesOfNestedRecord() {
        final var config = new TestConfigBuilder(false)
                .withConfigDataType(NestingConfig.class)
                .getOrCreateConfig();

        // the annotation is honoured on a property of a nested record, under its full name, while the component that
        // holds the group is not a property that can be set and is therefore never reported
        assertThat(Utils.networkProperties(config)).containsOnlyKeys("nesting.leaf.networkProperty");
    }

    @Test
    @DisplayName("allProperties() reports the properties of a nested config data object under their full names")
    void allPropertiesOfNestedRecord() {
        final var config = new TestConfigBuilder(false)
                .withConfigDataType(NestingConfig.class)
                .getOrCreateConfig();

        assertThat(Utils.allProperties(config))
                .containsKeys("nesting.leaf.networkProperty", "nesting.leaf.plainProperty")
                .doesNotContainKey("nesting.leaf");
    }

    @ConfigData("nullable")
    public record NullableConfig(
            @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
            String value) {}

    @ConfigData("nesting")
    public record NestingConfig(
            // annotating the group has no effect, the annotation belongs on the property itself
            @NetworkProperty NestedLeafConfig leaf) {}

    @NestedConfig
    public record NestedLeafConfig(
            @NetworkProperty @ConfigProperty(defaultValue = "a")
            String networkProperty,

            @ConfigProperty(defaultValue = "b") String plainProperty) {}
}
