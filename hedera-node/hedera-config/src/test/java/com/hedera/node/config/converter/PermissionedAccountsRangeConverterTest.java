// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.config.converter;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hedera.node.config.types.PermissionedAccountsRange;
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
        Assertions.assertThat(converted)
                .extracting(c -> c.from())
                .isEqualTo(testDataTuple.output().from());
        Assertions.assertThat(converted)
                .extracting(c -> c.inclusiveTo())
                .isEqualTo(testDataTuple.output().inclusiveTo());
    }

    final record TestDataTuple(String input, PermissionedAccountsRange output) {}

    static Stream<TestDataTuple> validDataProvider() {
        return Stream.of(
                new TestDataTuple("1-14", new PermissionedAccountsRange(1L, 14L)),
                new TestDataTuple("1-*", new PermissionedAccountsRange(1L, Long.MAX_VALUE)),
                new TestDataTuple("10-*", new PermissionedAccountsRange(10L, Long.MAX_VALUE)));
    }

    static Stream<String> invalidDataProvider() {
        return Stream.of("10-1", "*-1", "*-10", "1", "-1", "-1-10", "null", "1 - 10", "", "    ", " 1-10   ");
    }
}
