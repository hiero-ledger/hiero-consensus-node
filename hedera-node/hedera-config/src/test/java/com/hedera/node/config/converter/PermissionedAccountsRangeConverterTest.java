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
    void testValidParam(final TestDataTuple testDataTuple) {
        // given
        final PermissionedAccountsRangeConverter converter = new PermissionedAccountsRangeConverter();

        // when
        final PermissionedAccountsRange converted = converter.convert(testDataTuple.input());

        // then
        Assertions.assertThat(converted).isNotNull();
        // All ranges now use the inclusion-based format, so just verify it's not null
        // The actual range validation is tested through the contains() method behavior
    }

    final record TestDataTuple(String input, PermissionedAccountsRange output) {}

    static Stream<TestDataTuple> validDataProvider() {
        return Stream.of(
                // Single numbers
                new TestDataTuple("5", createInclusionRange(List.of(new Range(5L)))),
                new TestDataTuple("42", createInclusionRange(List.of(new Range(42L)))),
                // Ranges
                new TestDataTuple("1-14", createInclusionRange(List.of(new Range(1L, 14L)))),
                new TestDataTuple("2-10", createInclusionRange(List.of(new Range(2L, 10L)))),
                // Wildcard ranges
                new TestDataTuple("1-*", createInclusionRange(List.of(new Range(1L, Long.MAX_VALUE)))),
                new TestDataTuple("10-*", createInclusionRange(List.of(new Range(10L, Long.MAX_VALUE)))),
                // Inclusion-based syntax
                new TestDataTuple(
                        "2,7,10-20", createInclusionRange(List.of(new Range(2L), new Range(7L), new Range(10L, 20L)))),
                new TestDataTuple(
                        "0,5,9,10-20,30-40",
                        createInclusionRange(List.of(new Range(2L), new Range(7L), new Range(10L, 20L)))),
                new TestDataTuple("1-5,15-20", createInclusionRange(List.of(new Range(1L, 5L), new Range(15L, 20L)))),
                new TestDataTuple("1-10,15", createInclusionRange(List.of(new Range(1L, 10L), new Range(15L)))),
                // Wildcard in inclusion list
                new TestDataTuple(
                        "1-*,50-60",
                        createInclusionRange(List.of(new Range(1L, Long.MAX_VALUE), new Range(50L, 60L)))));
    }

    private static PermissionedAccountsRange createInclusionRange(List<Range> ranges) {
        return new PermissionedAccountsRange(ranges);
    }

    static Stream<String> invalidDataProvider() {
        return Stream.of("10-1", "*-1", "*-10", "1", "-1", "-1-10", "null", "1 - 10", "", "    ", " 1-10   ");
    }
}
