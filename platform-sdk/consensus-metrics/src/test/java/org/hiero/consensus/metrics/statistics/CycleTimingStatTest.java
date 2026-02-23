// SPDX-License-Identifier: Apache-2.0
package org.hiero.consensus.metrics.statistics;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.metrics.api.Metrics;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;
import org.hiero.consensus.metrics.noop.NoOpMetrics;
import org.hiero.consensus.metrics.statistics.cycle.CycleDefinition;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CycleTimingStatTest {

    private static Stream<Arguments> validConstructorArgs() {
        return Stream.of(
                Arguments.of("stat1", true, List.of("1", "2", "3"), List.of("d1", "d2", "d3")),
                Arguments.of("stat1", true, List.of("1", "2", "3", "4"), List.of("d1", "d2", "d3", "d4")),
                Arguments.of("stat1", true, List.of("1", "2"), List.of("d1", "d2")));
    }

    private static Stream<Arguments> invalidConstructorArgs() {
        return Stream.of(
                Arguments.of("stat1", false, List.of("1", "2", "3", "4"), List.of("d1", "d2", "d3")),
                Arguments.of("stat1", false, List.of("1", "2"), List.of("d1", "d2", "d3")));
    }

    @ParameterizedTest
    @MethodSource({"validConstructorArgs", "invalidConstructorArgs"})
    void testConstructor(
            final String name, final boolean validArgs, final List<String> detailedNames, final List<String> descList) {
        final Metrics metrics = new NoOpMetrics();
        final Runnable constructor = () -> new CycleTimingStat(
                metrics, ChronoUnit.MICROS, new CycleDefinition("cat", name, detailedNames, descList));
        if (validArgs) {
            assertDoesNotThrow(constructor::run);
        } else {
            assertThrows(IllegalArgumentException.class, constructor::run);
        }
    }
}
