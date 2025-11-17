// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.config.types.PermissionedAccountsRange;
import com.hedera.node.config.types.PermissionedAccountsRange.Range;
import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class PermissionedAccountsRangeConverterTest {

    @Test
    void testNullParam() {
        // given
        final PermissionedAccountsRangeConverter converter = new PermissionedAccountsRangeConverter();

        // then
        assertThatThrownBy(() -> converter.convert(null)).isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidDataProvider")
    void testInvalidParam() {
        // given
        final PermissionedAccountsRangeConverter converter = new PermissionedAccountsRangeConverter();

        // then
        assertThatThrownBy(() -> converter.convert("null")).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @MethodSource("validDataProvider")
    void testValidParam(final TestData testData) {
        // given
        final PermissionedAccountsRangeConverter converter = new PermissionedAccountsRangeConverter();

        // when
        final PermissionedAccountsRange converted = converter.convert(testData.input());

        // then
        Assertions.assertThat(converted).isNotNull();

        // Verify that all contained numbers are indeed contained
        for (Long number : testData.containedNumbers()) {
            Assertions.assertThat(converted.contains(number))
                    .withFailMessage(
                            "Number %d should be contained in range '%s' but was not", number, testData.input())
                    .isTrue();
        }

        // Verify that all not contained numbers are indeed not contained
        for (Long number : testData.notContainedNumbers()) {
            Assertions.assertThat(converted.contains(number))
                    .withFailMessage(
                            "Number %d should NOT be contained in range '%s' but was", number, testData.input())
                    .isFalse();
        }
    }

    private record TestData(
            String input,
            PermissionedAccountsRange output,
            List<Long> containedNumbers,
            List<Long> notContainedNumbers) {}

    static Stream<TestData> validDataProvider() {
        return Stream.of(
                // Single numbers
                new TestData("5", createInclusionRange(List.of(new Range(5L))), List.of(5L), List.of(4L, 6L)),
                new TestData("42", createInclusionRange(List.of(new Range(42L))), List.of(42L), List.of(41L, 43L)),
                // Ranges
                new TestData(
                        "1-14",
                        createInclusionRange(List.of(new Range(1L, 14L))),
                        List.of(1L, 7L, 14L),
                        List.of(0L, 15L)),
                new TestData(
                        "2-10",
                        createInclusionRange(List.of(new Range(2L, 10L))),
                        List.of(2L, 6L, 10L),
                        List.of(1L, 11L)),
                // Wildcard ranges
                new TestData(
                        "1-*",
                        createInclusionRange(List.of(new Range(1L, Long.MAX_VALUE))),
                        List.of(1L, 100L, Long.MAX_VALUE),
                        List.of(0L)),
                new TestData(
                        "10-*",
                        createInclusionRange(List.of(new Range(10L, Long.MAX_VALUE))),
                        List.of(10L, 1000L, Long.MAX_VALUE),
                        List.of(9L)),
                // Inclusion-based syntax
                new TestData(
                        "2,7,10-20",
                        createInclusionRange(List.of(new Range(2L), new Range(7L), new Range(10L, 20L))),
                        List.of(2L, 7L, 10L, 15L, 20L),
                        List.of(1L, 3L, 6L, 8L, 9L, 21L)),
                new TestData(
                        "0,5,9,10-20,30-40",
                        createInclusionRange(List.of(
                                new Range(0L), new Range(5L), new Range(9L), new Range(10L, 20L), new Range(30L, 40L))),
                        List.of(0L, 5L, 9L, 10L, 15L, 20L, 30L, 35L, 40L),
                        List.of(-1L, 1L, 4L, 6L, 8L, 21L, 29L, 41L)),
                new TestData(
                        "1-5,15-20",
                        createInclusionRange(List.of(new Range(1L, 5L), new Range(15L, 20L))),
                        List.of(1L, 3L, 5L, 15L, 18L, 20L),
                        List.of(0L, 6L, 14L, 21L)),
                new TestData(
                        "1-10,15",
                        createInclusionRange(List.of(new Range(1L, 10L), new Range(15L))),
                        List.of(1L, 5L, 10L, 15L),
                        List.of(0L, 11L, 14L, 16L)),
                // Wildcard in inclusion list
                new TestData(
                        "1-*,50-60",
                        createInclusionRange(List.of(new Range(1L, Long.MAX_VALUE), new Range(50L, 60L))),
                        List.of(1L, 25L, 50L, 55L, 60L, Long.MAX_VALUE),
                        List.of(0L)));
    }

    private static PermissionedAccountsRange createInclusionRange(List<Range> ranges) {
        return new PermissionedAccountsRange(ranges);
    }

    static Stream<String> invalidDataProvider() {
        return Stream.of("10-1", "*-1", "*-10", "1", "-1", "-1-10", "null", "1 - 10", "", "    ", " 1-10   ");
    }
}
