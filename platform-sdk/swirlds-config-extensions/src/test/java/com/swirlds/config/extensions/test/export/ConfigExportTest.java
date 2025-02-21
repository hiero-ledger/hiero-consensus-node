// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.extensions.test.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.ext.swirlds.config.extensions.test.ConfigExportTestConstants.ConfigExportTestRecord;
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
                .anySatisfy(value -> assertThat(value).matches("^property, value\\s*$"))
                .anySatisfy(value -> assertThat(value).matches("^prefix.property, anotherValue\\s*$"))
                // Verify properties not in file are listed (spot check only)
                .anySatisfy(value -> assertThat(value)
                        .matches("^prefix.unmappedProperty, notPresentValue\\s*\\[NOT USED IN RECORD]$"))
                .anySatisfy(value -> assertThat(value)
                        .matches("^unmappedProperty, anotherNotPresentValue\\s*\\[NOT USED IN RECORD]$"));
    }
}
