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

    @Test
    @DisplayName("allProperties() reports nothing below a nested config data object that defaults to null")
    void allPropertiesOfAbsentOptionalNestedRecord() {
        final var config = new TestConfigBuilder(false)
                .withConfigDataType(OptionalNestingConfig.class)
                .getOrCreateConfig();

        // the group is what does not exist, not the value of one of its properties, so unlike a single property that
        // defaults to null there is nothing below it to report
        assertThat(Utils.allProperties(config))
                .doesNotContainKey("optionalNesting.leaf")
                .doesNotContainKey("optionalNesting.leaf.networkProperty")
                .doesNotContainKey("optionalNesting.leaf.plainProperty");
    }

    @Test
    @DisplayName("networkProperties() reports nothing below a nested config data object that defaults to null")
    void networkPropertiesOfAbsentOptionalNestedRecord() {
        final var config = new TestConfigBuilder(false)
                .withConfigDataType(OptionalNestingConfig.class)
                .getOrCreateConfig();

        assertThat(Utils.networkProperties(config)).isEmpty();
    }

    @ConfigData("nullable")
    public record NullableConfig(
            @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
            String value) {}

    @ConfigData("nesting")
    public record NestingConfig(
            // annotating the group has no effect, the annotation belongs on the property itself
            @NetworkProperty NestedLeafConfig leaf) {}

    @ConfigData("optionalNesting")
    public record OptionalNestingConfig(
            @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
            NestedLeafConfig leaf) {}

    @NestedConfig
    public record NestedLeafConfig(
            @NetworkProperty @ConfigProperty(defaultValue = "a")
            String networkProperty,

            @ConfigProperty(defaultValue = "b") String plainProperty) {}
}
