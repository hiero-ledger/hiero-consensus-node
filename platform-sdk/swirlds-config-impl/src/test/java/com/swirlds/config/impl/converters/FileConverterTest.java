// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import java.io.File;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class FileConverterTest {

    @Test
    public void convertNull() {
        // given
        final FileConverter converter = new FileConverter();

        // then
        Assertions.assertThrows(NullPointerException.class, () -> converter.convert(null));
    }

    @Test
    public void convert() {
        // given
        final FileConverter converter = new FileConverter();
        final String rawValue = "test.txt";

        // when
        final File value = converter.convert(rawValue);

        // then
        Assertions.assertNotNull(value);
        Assertions.assertEquals("test.txt", value.getName());
    }
}
