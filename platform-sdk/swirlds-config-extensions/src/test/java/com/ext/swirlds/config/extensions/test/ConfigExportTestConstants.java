// SPDX-License-Identifier: Apache-2.0
package com.ext.swirlds.config.extensions.test;

import com.swirlds.config.api.ConfigData;
import com.swirlds.config.api.ConfigDefault;
import com.swirlds.config.api.ConfigProperty;

/**
 * Helper for {@code ConfigExportTest}
 */
public class ConfigExportTestConstants {

    // Following classes are inner to this one so that they are outside the com.swirlds package and avoids being picked
    // up
    // by the framework. They are specifically added in ConfigExportTest
    @ConfigData
    public record ConfigExportTestRecord(String property) {}

    @ConfigData("prefix")
    public record PrefixedConfigExportTestRecord(String property) {}

    @ConfigData("nested")
    public record NestedConfigExportTestRecord(
            @ConfigDefault(property = "value", defaultValue = "defaultValue")
            @ConfigDefault(property = "count", defaultValue = "1")
            NestedLeaf leaf) {}

    @ConfigData
    public record NestedLeaf(String value, int count) {}

    @ConfigData("nullable")
    public record NullableConfigExportTestRecord(
            @ConfigProperty(defaultValue = ConfigProperty.NULL_DEFAULT_VALUE)
            String value) {}
}
