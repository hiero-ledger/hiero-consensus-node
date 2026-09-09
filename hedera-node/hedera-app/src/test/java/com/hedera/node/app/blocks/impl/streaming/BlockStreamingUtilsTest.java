// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class BlockStreamingUtilsTest {

    @ParameterizedTest
    @MethodSource("parseToBytesArgs")
    void testParseToBytes(final String input, final long expectedValue) {
        final long actualValue = BlockStreamingUtils.parseToBytes(input);
        assertThat(actualValue).isEqualTo(expectedValue);
    }

    static Stream<Arguments> parseToBytesArgs() {
        return Stream.of(
                Arguments.of(null, -1L),
                Arguments.of("", -1L),
                Arguments.of("        ", -1L),
                Arguments.of(" 10000  ", 10_000L),
                Arguments.of(" 100k", 102_400L),
                Arguments.of("500K ", 512_000L),
                Arguments.of("10m", 10_485_760L),
                Arguments.of("25M  ", 26_214_400L),
                Arguments.of("  1g", 1_073_741_824L),
                Arguments.of(" 18G ", 19_327_352_832L),
                Arguments.of("1T", -1),
                Arguments.of("100 K", -1),
                Arguments.of("100   K", -1),
                Arguments.of("-1K", -1),
                Arguments.of("1KB", -1),
                Arguments.of(".G", -1),
                Arguments.of("M100", -1),
                Arguments.of("10MM", -1),
                Arguments.of("sdfkj dkj 39dk 1", -1),
                Arguments.of("-10029381", -1));
    }
}
