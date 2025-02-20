// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PathConverterTest {

    @Test
    public void convertNull() {
        // given
        final PathConverter converter = new PathConverter();

        // then
        Assertions.assertThrows(NullPointerException.class, () -> converter.convert(null));
    }

    @Test
    public void convert() {
        // given
        final PathConverter converter = new PathConverter();
        final String rawValue = "test.txt";

        // when
        final Path value = converter.convert(rawValue);

        // then
        Assertions.assertNotNull(value);
        Assertions.assertEquals("test.txt", value.toFile().getName());
    }
}
