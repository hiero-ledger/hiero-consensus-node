// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.jackson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonParser;
import java.io.IOException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the {@link InstantDeserializer} class.
 */
public class InstantDeserializerTests {

    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {"   "})
    void testDeserializeMissingValue(final String invalidValue) throws IOException {
        final JsonParser jp = mock(JsonParser.class);
        when(jp.getValueAsString()).thenReturn(invalidValue);
        assertNull(new InstantDeserializer().deserialize(jp, null), "Invalid values should return  null");
    }

    @Test
    void testDeserializeValidValues() throws IOException {
        final Instant now = Instant.now();
        final JsonParser jp = mock(JsonParser.class);
        when(jp.getValueAsString()).thenReturn(now.toString());
        assertEquals(now, new InstantDeserializer().deserialize(jp, null), "Incorrect deserialized value");
    }
}
