// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import java.net.URL;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class UrlConverterTest {

    @Test
    public void convertNull() {
        // given
        final UrlConverter converter = new UrlConverter();

        // then
        Assertions.assertThrows(NullPointerException.class, () -> converter.convert(null));
    }

    @Test
    public void convert() {
        // given
        final UrlConverter converter = new UrlConverter();
        final String rawValue = "http://example.net";

        // when
        final URL value = converter.convert(rawValue);

        // then
        Assertions.assertNotNull(value);
        Assertions.assertEquals("http://example.net", value.toString());
    }

    @Test
    public void convertBadUrl() {
        // given
        final UrlConverter converter = new UrlConverter();
        final String rawValue = "{invalid:url}";

        // then
        Assertions.assertThrows(IllegalArgumentException.class, () -> converter.convert(rawValue));
    }
}
