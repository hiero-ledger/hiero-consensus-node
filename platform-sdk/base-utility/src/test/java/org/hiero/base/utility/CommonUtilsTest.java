// SPDX-License-Identifier: Apache-2.0
package org.hiero.base.utility;

import static org.hiero.base.utility.CommonUtils.hex;
import static org.hiero.base.utility.CommonUtils.unhex;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommonUtilsTest {
    private static final byte[] HEX_BYTES = {0x12, 0x34, 0x56, 0x78, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f};
    private static final String HEX_STRING = "123456780a0b0c0d0e0f";

    @Test
    void hexTest() {
        assertTrue(hex((byte[]) null).contains("null"), "the output of a null input should indicate its null");
        assertEquals("", hex(new byte[0]), "for an empty array we should get an empty string");

        assertEquals(HEX_STRING, hex(HEX_BYTES), "hex value should match");
        final int length = 2;
        assertEquals(HEX_STRING.substring(0, length * 2), hex(HEX_BYTES, length), "hex value should match");

        assertThrows(
                IllegalArgumentException.class,
                () -> hex(HEX_BYTES, HEX_BYTES.length + 1),
                "should throw if illegal length");
    }

    @Test
    void unhexTest() {
        assertNull(unhex(null), "null input should provide null output");
        assertThrows(
                IllegalArgumentException.class,
                () -> unhex("123"),
                "a hex string can never have a odd number of characters");
        assertArrayEquals(HEX_BYTES, unhex(HEX_STRING), "hex value should match");
        assertArrayEquals(HEX_BYTES, unhex(HEX_STRING.toUpperCase()), "hex value should match");

        assertThrows(
                IllegalArgumentException.class,
                () -> unhex("a random string"),
                "hex characters should be in the range: [A-Fa-f0-9]");
    }

    @Test
    void decodeHexValid() {
        // Given
        final String hex = "68656c6c6f";

        // When
        final byte[] result = unhex(hex);

        // Then
        final byte[] expected = "hello".getBytes();
        assertArrayEquals(expected, result);
    }

    @Test
    void decodeHexEmptyString() {
        // Given
        final String hex = "";

        // When
        final byte[] result = unhex(hex);

        // Then
        final byte[] expected = new byte[0];
        assertArrayEquals(expected, result);
    }

    @Test
    void decodeHexInvalidOddNumberOfChars() {
        final String hex = "6865e"; // Odd number of characters
        assertThrows(IllegalArgumentException.class, () -> unhex(hex));
    }

    @Test
    void decodeHexInvalidCharacter() {
        final String hex = "68656c6c6g"; // Contains an invalid character "g"
        assertThrows(IllegalArgumentException.class, () -> unhex(hex));
    }

    @Test
    void hexWithNullBytes() {
        final byte[] bytes = null;
        assertEquals("null", hex(bytes, 0));
    }

    @Test
    void hexWithValidBytes() {
        // Given
        final byte[] bytes = "hello".getBytes();

        // When
        final String result = hex(bytes, bytes.length);

        // Then
        assertEquals("68656c6c6f", result);
    }

    @Test
    void hexWithLengthTooLow() {
        final byte[] bytes = "hello".getBytes();
        assertThrows(
                IllegalArgumentException.class,
                () -> hex(bytes, -1)); // Assuming throwRangeInvalid throws IllegalArgumentException
    }

    @Test
    void hexWithLengthTooHigh() {
        final byte[] bytes = "hello".getBytes();
        assertThrows(
                IllegalArgumentException.class,
                () -> hex(bytes, bytes.length + 1)); // Assuming throwRangeInvalid throws IllegalArgumentException
    }
}
