// SPDX-License-Identifier: Apache-2.0
package com.swirlds.common.formatting;

import static com.swirlds.common.formatting.StringFormattingUtils.addLine;
import static com.swirlds.common.formatting.StringFormattingUtils.commaSeparatedNumber;
import static com.swirlds.common.formatting.StringFormattingUtils.formattedList;
import static com.swirlds.common.formatting.StringFormattingUtils.parseSanitizedTimestamp;
import static com.swirlds.common.formatting.StringFormattingUtils.repeatedChar;
import static com.swirlds.common.formatting.StringFormattingUtils.sanitizeTimestamp;
import static org.hiero.base.utility.test.fixtures.RandomUtils.getRandomPrintSeed;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.Random;
import org.hiero.base.utility.test.fixtures.RandomUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StringFormattingUtilities Tests")
class StringFormattingUtilsTests {

    @Test
    @DisplayName("Formatted List  Empty String Test")
    @SuppressWarnings("RedundantOperationOnEmptyContainer")
    void formattedListEmptyStringTest() {
        final List<Integer> data = List.of();

        assertEquals("", formattedList(data.iterator()));
        assertEquals("", formattedList(data.iterator(), ", "));
        assertEquals("", formattedList(data.iterator(), " - "));

        {
            final StringBuilder sb = new StringBuilder();
            formattedList(sb, data.iterator());
            assertEquals("", sb.toString());
        }

        {
            final StringBuilder sb = new StringBuilder();
            formattedList(sb, data.iterator(), ", ");
            assertEquals("", sb.toString());
        }

        {
            final StringBuilder sb = new StringBuilder();
            formattedList(sb, data.iterator(), " - ");
            assertEquals("", sb.toString());
        }
    }

    @Test
    @DisplayName("Formatted List Single Element Test")
    void formattedListSingleElementTest() {
        final List<Integer> data = List.of(1234);

        assertEquals("1234", formattedList(data.iterator()));
        assertEquals("1234", formattedList(data.iterator(), ", "));
        assertEquals("1234", formattedList(data.iterator(), " - "));

        {
            final StringBuilder sb = new StringBuilder();
            formattedList(sb, data.iterator());
            assertEquals("1234", sb.toString());
        }

        {
            final StringBuilder sb = new StringBuilder();
            formattedList(sb, data.iterator(), ", ");
            assertEquals("1234", sb.toString());
        }

        {
            final StringBuilder sb = new StringBuilder();
            formattedList(sb, data.iterator(), " - ");
            assertEquals("1234", sb.toString());
        }
    }

    @Test
    @DisplayName("Formatted List Multiple Elements Test")
    void formattedListMultipleElementsTest() {
        final List<Integer> data = List.of(1234, 5678, 9012, 3456, 7890);

        assertEquals("1234, 5678, 9012, 3456, 7890", formattedList(data.iterator()));
        assertEquals("1234, 5678, 9012, 3456, 7890", formattedList(data.iterator(), ", "));
        assertEquals("1234 - 5678 - 9012 - 3456 - 7890", formattedList(data.iterator(), " - "));

        {
            final StringBuilder sb = new StringBuilder();
            formattedList(sb, data.iterator());
            assertEquals("1234, 5678, 9012, 3456, 7890", sb.toString());
        }

        {
            final StringBuilder sb = new StringBuilder();
            formattedList(sb, data.iterator(), ", ");
            assertEquals("1234, 5678, 9012, 3456, 7890", sb.toString());
        }

        {
            final StringBuilder sb = new StringBuilder();
            formattedList(sb, data.iterator(), " - ");
            assertEquals("1234 - 5678 - 9012 - 3456 - 7890", sb.toString());
        }
    }

    @Test
    @DisplayName("addLine() Test")
    void addLineTest() {
        final StringBuilder sb = new StringBuilder();
        addLine(sb, "Hello");
        addLine(sb, "World");
        addLine(sb, "!");
        addLine(sb, "this is a test of the emergency testing system");

        final String expected = "Hello\nWorld\n!\nthis is a test of the emergency testing system\n";
        assertEquals(expected, sb.toString());
    }

    @Test
    @DisplayName("repeatedChar() Test")
    void repeatedCharTest() {
        assertEquals("", repeatedChar('a', -1));
        assertEquals("", repeatedChar('a', 0));
        assertEquals("a", repeatedChar('a', 1));
        assertEquals("aaaa", repeatedChar('a', 4));
    }

    @Test
    @DisplayName("commaSeparatedNumber() long Test")
    void commaSeparatedNumberLongTest() {
        assertEquals("0", commaSeparatedNumber(0));
        assertEquals("-1", commaSeparatedNumber(-1));
        assertEquals("1", commaSeparatedNumber(1));
        assertEquals("42", commaSeparatedNumber(42));
        assertEquals("-42", commaSeparatedNumber(-42));
        assertEquals("123", commaSeparatedNumber(123));
        assertEquals("-123", commaSeparatedNumber(-123));
        assertEquals("1,234", commaSeparatedNumber(1234));
        assertEquals("-1,234", commaSeparatedNumber(-1234));
        assertEquals("12,345", commaSeparatedNumber(12345));
        assertEquals("-12,345", commaSeparatedNumber(-12345));
        assertEquals("123,456", commaSeparatedNumber(123456));
        assertEquals("-123,456", commaSeparatedNumber(-123456));
        assertEquals("1,234,567", commaSeparatedNumber(1234567));
        assertEquals("-1,234,567", commaSeparatedNumber(-1234567));
        assertEquals("12,345,678", commaSeparatedNumber(12345678));
        assertEquals("-12,345,678", commaSeparatedNumber(-12345678));
        assertEquals("123,456,789", commaSeparatedNumber(123456789));
        assertEquals("-123,456,789", commaSeparatedNumber(-123456789));
        assertEquals("1,234,567,898", commaSeparatedNumber(1234567898));
        assertEquals("-1,234,567,898", commaSeparatedNumber(-1234567898));
        assertEquals("12,345,678,987", commaSeparatedNumber(12345678987L));
        assertEquals("-12,345,678,987", commaSeparatedNumber(-12345678987L));
        assertEquals("123,456,789,876", commaSeparatedNumber(123456789876L));
        assertEquals("-123,456,789,876", commaSeparatedNumber(-123456789876L));
        assertEquals("1,234,567,898,765", commaSeparatedNumber(1234567898765L));
        assertEquals("-1,234,567,898,765", commaSeparatedNumber(-1234567898765L));
        assertEquals("12,345,678,987,654", commaSeparatedNumber(12345678987654L));
        assertEquals("-12,345,678,987,654", commaSeparatedNumber(-12345678987654L));
        assertEquals("123,456,789,876,543", commaSeparatedNumber(123456789876543L));
        assertEquals("-123,456,789,876,543", commaSeparatedNumber(-123456789876543L));
        assertEquals("1,234,567,898,765,432", commaSeparatedNumber(1234567898765432L));
        assertEquals("-1,234,567,898,765,432", commaSeparatedNumber(-1234567898765432L));
        assertEquals("12,345,678,987,654,321", commaSeparatedNumber(12345678987654321L));
        assertEquals("-12,345,678,987,654,321", commaSeparatedNumber(-12345678987654321L));
    }

    @Test
    @DisplayName("commaSeparatedNumber() double Test")
    void commaSeparatedNumberDoubleTest() {
        assertEquals("0", commaSeparatedNumber(0.0, 0));
        assertEquals("0.0", commaSeparatedNumber(0.0, 1));
        assertEquals("0.00", commaSeparatedNumber(0.0, 2));
        assertEquals("0.000", commaSeparatedNumber(0.0, 3));

        assertEquals("1", commaSeparatedNumber(1.0, 0));
        assertEquals("1.0", commaSeparatedNumber(1.0, 1));
        assertEquals("1.00", commaSeparatedNumber(1.0, 2));
        assertEquals("1.000", commaSeparatedNumber(1.0, 3));

        assertEquals("-1", commaSeparatedNumber(-1.0, 0));
        assertEquals("-1.0", commaSeparatedNumber(-1.0, 1));
        assertEquals("-1.00", commaSeparatedNumber(-1.0, 2));
        assertEquals("-1.000", commaSeparatedNumber(-1.0, 3));

        assertEquals("1", commaSeparatedNumber(1.1234567, 0));
        assertEquals("2.1", commaSeparatedNumber(2.1234567, 1));
        assertEquals("3.12", commaSeparatedNumber(3.1234567, 2));
        assertEquals("4.123", commaSeparatedNumber(4.1234567, 3));
        assertEquals("5.1235", commaSeparatedNumber(5.1234567, 4));
        assertEquals("6.12346", commaSeparatedNumber(6.1234567, 5));
        assertEquals("7.123457", commaSeparatedNumber(7.1234567, 6));
        assertEquals("8.1234567", commaSeparatedNumber(8.1234567, 7));
        assertEquals("9.12345670", commaSeparatedNumber(9.1234567, 8));

        assertEquals("-1", commaSeparatedNumber(-1.1234567, 0));
        assertEquals("-2.1", commaSeparatedNumber(-2.1234567, 1));
        assertEquals("-3.12", commaSeparatedNumber(-3.1234567, 2));
        assertEquals("-4.123", commaSeparatedNumber(-4.1234567, 3));
        assertEquals("-5.1235", commaSeparatedNumber(-5.1234567, 4));
        assertEquals("-6.12346", commaSeparatedNumber(-6.1234567, 5));
        assertEquals("-7.123457", commaSeparatedNumber(-7.1234567, 6));
        assertEquals("-8.1234567", commaSeparatedNumber(-8.1234567, 7));
        assertEquals("-9.12345670", commaSeparatedNumber(-9.1234567, 8));

        assertEquals("2", commaSeparatedNumber(1.6666666, 0));
        assertEquals("1.7", commaSeparatedNumber(1.6666666, 1));
        assertEquals("1.67", commaSeparatedNumber(1.6666666, 2));
        assertEquals("1.667", commaSeparatedNumber(1.6666666, 3));
        assertEquals("12", commaSeparatedNumber(11.6666666, 0));
        assertEquals("11.7", commaSeparatedNumber(11.6666666, 1));
        assertEquals("11.67", commaSeparatedNumber(11.6666666, 2));
        assertEquals("11.667", commaSeparatedNumber(11.6666666, 3));
        assertEquals("112", commaSeparatedNumber(111.6666666, 0));
        assertEquals("111.7", commaSeparatedNumber(111.6666666, 1));
        assertEquals("111.67", commaSeparatedNumber(111.6666666, 2));
        assertEquals("111.667", commaSeparatedNumber(111.6666666, 3));
        assertEquals("1,112", commaSeparatedNumber(1111.6666666, 0));
        assertEquals("1,111.7", commaSeparatedNumber(1111.6666666, 1));
        assertEquals("1,111.67", commaSeparatedNumber(1111.6666666, 2));
        assertEquals("1,111.667", commaSeparatedNumber(1111.6666666, 3));

        assertEquals("-2", commaSeparatedNumber(-1.6666666, 0));
        assertEquals("-1.7", commaSeparatedNumber(-1.6666666, 1));
        assertEquals("-1.67", commaSeparatedNumber(-1.6666666, 2));
        assertEquals("-1.667", commaSeparatedNumber(-1.6666666, 3));
        assertEquals("-12", commaSeparatedNumber(-11.6666666, 0));
        assertEquals("-11.7", commaSeparatedNumber(-11.6666666, 1));
        assertEquals("-11.67", commaSeparatedNumber(-11.6666666, 2));
        assertEquals("-11.667", commaSeparatedNumber(-11.6666666, 3));
        assertEquals("-112", commaSeparatedNumber(-111.6666666, 0));
        assertEquals("-111.7", commaSeparatedNumber(-111.6666666, 1));
        assertEquals("-111.67", commaSeparatedNumber(-111.6666666, 2));
        assertEquals("-111.667", commaSeparatedNumber(-111.6666666, 3));
        assertEquals("-1,112", commaSeparatedNumber(-1111.6666666, 0));
        assertEquals("-1,111.7", commaSeparatedNumber(-1111.6666666, 1));
        assertEquals("-1,111.67", commaSeparatedNumber(-1111.6666666, 2));
        assertEquals("-1,111.667", commaSeparatedNumber(-1111.6666666, 3));
    }

    @Test
    @DisplayName("Sanitized Timestamp Test")
    void sanitizedTimestampTest() {
        final Random random = getRandomPrintSeed();
        final Instant original = RandomUtils.randomInstant(random);
        final String serialized = sanitizeTimestamp(original);
        final Instant deserialized = parseSanitizedTimestamp(serialized);
        assertEquals(original, deserialized);
    }
}
