// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.test.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.ext.swirlds.config.extensions.test.ConfigExportTestConstants.ConfigExportTestRecord;
import com.ext.swirlds.config.extensions.test.ConfigExportTestConstants.NestedConfigExportTestRecord;
import com.ext.swirlds.config.extensions.test.ConfigExportTestConstants.NullableConfigExportTestRecord;
import com.ext.swirlds.config.extensions.test.ConfigExportTestConstants.PrefixedConfigExportTestRecord;
import com.swirlds.config.api.Configuration;
import com.swirlds.config.api.ConfigurationBuilder;
import com.swirlds.config.extensions.export.ConfigExport;
import com.swirlds.config.extensions.sources.SimpleConfigSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ConfigExportTest {

    @Test
    void testPrint() throws IOException {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withConfigDataType(ConfigExportTestRecord.class)
                .withConfigDataType(PrefixedConfigExportTestRecord.class)
                .withSource(new SimpleConfigSource("property", "value"))
                .withSource(new SimpleConfigSource("prefix.property", "anotherValue"))
                .withSource(new SimpleConfigSource("prefix.unmappedProperty", "notPresentValue"))
                .withSource(new SimpleConfigSource("unmappedProperty", "anotherNotPresentValue"))
                .build();
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // when
        ConfigExport.printConfig(configuration, outputStream);
        final List<String> lines =
                outputStream.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());

        // then
        Assertions.assertNotNull(lines);
        Assertions.assertFalse(lines.isEmpty());

        assertThat(lines)
                .as("All values of the exported configuration")
                .isNotNull()
                .isNotEmpty()
                // Verify properties in file are listed
                .anySatisfy(value -> assertThat(value).matches("^property, value$"))
                .anySatisfy(value -> assertThat(value).matches("^prefix.property, anotherValue$"))
                // Verify properties not in file are listed (spot check only)
                .anySatisfy(value ->
                        assertThat(value).matches("^prefix.unmappedProperty, notPresentValue  \\[NOT USED IN RECORD]$"))
                .anySatisfy(value -> assertThat(value)
                        .matches("^unmappedProperty, anotherNotPresentValue  \\[NOT USED IN RECORD]$"));
    }

    @Test
    void testPrintPropertyWithNullValue() throws IOException {
        // given a property whose value is null, which rules out Collectors.toMap when collecting the properties
        final Configuration configuration = ConfigurationBuilder.create()
                .withConfigDataType(NullableConfigExportTestRecord.class)
                .build();
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // when
        ConfigExport.printConfig(configuration, outputStream);
        final List<String> lines =
                outputStream.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());

        // then the property is exported instead of the export failing
        assertThat(lines).anySatisfy(value -> assertThat(value).matches("^nullable.value, null$"));
    }

    @Test
    void testPrintNestedConfigDataObject() throws IOException {
        // given
        final Configuration configuration = ConfigurationBuilder.create()
                .withConfigDataType(NestedConfigExportTestRecord.class)
                .withSource(new SimpleConfigSource("nested.leaf.count", "7"))
                .build();
        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // when
        ConfigExport.printConfig(configuration, outputStream);
        final List<String> lines =
                outputStream.toString(StandardCharsets.UTF_8).lines().collect(Collectors.toList());

        // then the properties of the nested record are exported individually and are not reported as unused
        assertThat(lines)
                .as("All values of the exported configuration")
                .anySatisfy(value -> assertThat(value).matches("^nested.leaf.value, defaultValue$"))
                .anySatisfy(value -> assertThat(value).matches("^nested.leaf.count, 7$"))
                .noneSatisfy(value -> assertThat(value).contains("nested.leaf.count", "NOT USED IN RECORD"))
                // the component holding the nested record has no value of its own and can not be set, so exporting it
                // would print the toString of the record for a property that does not exist
                .noneSatisfy(value -> assertThat(value).startsWith("nested.leaf,"));
    }
}
