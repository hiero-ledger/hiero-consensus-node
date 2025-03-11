// SPDX-License-Identifier: Apache-2.0
package com.swirlds.config.impl.converters;

import java.net.URI;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class UriConverterTest {

    @Test
    public void convertNull() {
        // given
        final UriConverter converter = new UriConverter();

        // then
        Assertions.assertThrows(NullPointerException.class, () -> converter.convert(null));
    }

    @Test
    public void convert() {
        // given
        final UriConverter converter = new UriConverter();
        final String rawValue = "http://example.net";

        // when
        final URI value = converter.convert(rawValue);

        // then
        Assertions.assertNotNull(value);
        Assertions.assertEquals("http://example.net", value.toString());
    }

    @Test
    public void convertBadUri() {
        // given
        final UriConverter converter = new UriConverter();
        final String rawValue = "{invalid:uri}";

        // then
        Assertions.assertThrows(IllegalArgumentException.class, () -> converter.convert(rawValue));
    }
}
