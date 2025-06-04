// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.base.test.fixtures.time.FakeTime;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link PcesUtilities}
 */
class PcesUtilitiesTests {
    private FakeTime time;

    @BeforeEach
    void setup() {
        time = new FakeTime();
        time.tick(Duration.ofSeconds(100));
    }

    @Test
    @DisplayName("Standard operation")
    void standardOperation() {
        final PcesFile previousFileDescriptor = PcesFile.of(time.now(), 2, 10, 20, 5, Path.of("root"));
        final PcesFile currentFileDescriptor = PcesFile.of(
                time.now(),
                previousFileDescriptor.getSequenceNumber() + 1,
                previousFileDescriptor.getLowerBound(),
                previousFileDescriptor.getUpperBound(),
                previousFileDescriptor.getOrigin(),
                Path.of("root"));

        assertDoesNotThrow(() -> PcesUtilities.fileSanityChecks(
                false,
                previousFileDescriptor.getSequenceNumber(),
                previousFileDescriptor.getLowerBound(),
                previousFileDescriptor.getUpperBound(),
                previousFileDescriptor.getOrigin(),
                previousFileDescriptor.getTimestamp(),
                currentFileDescriptor));
    }

    @Test
    @DisplayName("Decreasing sequence number")
    void decreasingSequenceNumber() {
        final PcesFile previousFileDescriptor = PcesFile.of(time.now(), 2, 10, 20, 5, Path.of("root"));
        final PcesFile currentFileDescriptor = PcesFile.of(
                time.now(),
                previousFileDescriptor.getSequenceNumber() - 1,
                previousFileDescriptor.getLowerBound(),
                previousFileDescriptor.getUpperBound(),
                previousFileDescriptor.getOrigin(),
                Path.of("root"));

        assertThrows(
                IllegalStateException.class,
                () -> PcesUtilities.fileSanityChecks(
                        false,
                        previousFileDescriptor.getSequenceNumber(),
                        previousFileDescriptor.getLowerBound(),
                        previousFileDescriptor.getUpperBound(),
                        previousFileDescriptor.getOrigin(),
                        previousFileDescriptor.getTimestamp(),
                        currentFileDescriptor));
    }

    @Test
    @DisplayName("Decreasing sequence number with gaps permitted")
    void decreasingSequenceNumberWithGapsPermitted() {
        final PcesFile previousFileDescriptor = PcesFile.of(time.now(), 2, 10, 20, 5, Path.of("root"));
        final PcesFile currentFileDescriptor = PcesFile.of(
                time.now(),
                previousFileDescriptor.getSequenceNumber() - 1,
                previousFileDescriptor.getLowerBound(),
                previousFileDescriptor.getUpperBound(),
                previousFileDescriptor.getOrigin(),
                Path.of("root"));

        assertDoesNotThrow(() -> PcesUtilities.fileSanityChecks(
                true,
                previousFileDescriptor.getSequenceNumber(),
                previousFileDescriptor.getLowerBound(),
                previousFileDescriptor.getUpperBound(),
                previousFileDescriptor.getOrigin(),
                previousFileDescriptor.getTimestamp(),
                currentFileDescriptor));
    }

    @Test
    @DisplayName("Non-increasing sequence number")
    void nonIncreasingSequenceNumber() {
        final PcesFile previousFileDescriptor = PcesFile.of(time.now(), 2, 10, 20, 5, Path.of("root"));
        final PcesFile currentFileDescriptor = PcesFile.of(
                time.now(),
                previousFileDescriptor.getSequenceNumber(),
                previousFileDescriptor.getLowerBound(),
                previousFileDescriptor.getUpperBound(),
                previousFileDescriptor.getOrigin(),
                Path.of("root"));

        assertThrows(
                IllegalStateException.class,
                () -> PcesUtilities.fileSanityChecks(
                        false,
                        previousFileDescriptor.getSequenceNumber(),
                        previousFileDescriptor.getLowerBound(),
                        previousFileDescriptor.getUpperBound(),
                        previousFileDescriptor.getOrigin(),
                        previousFileDescriptor.getTimestamp(),
                        currentFileDescriptor));
    }

    @Test
    @DisplayName("Decreasing Lower Bound")
    void decreasingMinimumLowerBound() {
        final PcesFile previousFileDescriptor = PcesFile.of(time.now(), 2, 10, 20, 5, Path.of("root"));
        final PcesFile currentFileDescriptor = PcesFile.of(
                time.now(),
                previousFileDescriptor.getSequenceNumber() + 1,
                previousFileDescriptor.getLowerBound() - 1,
                previousFileDescriptor.getUpperBound(),
                previousFileDescriptor.getOrigin(),
                Path.of("root"));

        assertThrows(
                IllegalStateException.class,
                () -> PcesUtilities.fileSanityChecks(
                        false,
                        previousFileDescriptor.getSequenceNumber(),
                        previousFileDescriptor.getLowerBound(),
                        previousFileDescriptor.getUpperBound(),
                        previousFileDescriptor.getOrigin(),
                        previousFileDescriptor.getTimestamp(),
                        currentFileDescriptor));
    }

    @Test
    @DisplayName("Decreasing Upper Bound")
    void decreasingUpperBound() {
        final PcesFile previousFileDescriptor = PcesFile.of(time.now(), 2, 10, 20, 5, Path.of("root"));
        final PcesFile currentFileDescriptor = PcesFile.of(
                time.now(),
                previousFileDescriptor.getSequenceNumber() + 1,
                previousFileDescriptor.getLowerBound(),
                previousFileDescriptor.getUpperBound() - 1,
                previousFileDescriptor.getOrigin(),
                Path.of("root"));

        assertThrows(
                IllegalStateException.class,
                () -> PcesUtilities.fileSanityChecks(
                        false,
                        previousFileDescriptor.getSequenceNumber(),
                        previousFileDescriptor.getLowerBound(),
                        previousFileDescriptor.getUpperBound(),
                        previousFileDescriptor.getOrigin(),
                        previousFileDescriptor.getTimestamp(),
                        currentFileDescriptor));
    }

    @Test
    @DisplayName("Decreasing timestamp")
    void decreasingTimestamp() {
        final PcesFile previousFileDescriptor = PcesFile.of(time.now(), 2, 10, 20, 5, Path.of("root"));
        final PcesFile currentFileDescriptor = PcesFile.of(
                previousFileDescriptor.getTimestamp().minusSeconds(10),
                previousFileDescriptor.getSequenceNumber() + 1,
                previousFileDescriptor.getLowerBound(),
                previousFileDescriptor.getUpperBound(),
                previousFileDescriptor.getOrigin(),
                Path.of("root"));

        assertThrows(
                IllegalStateException.class,
                () -> PcesUtilities.fileSanityChecks(
                        false,
                        previousFileDescriptor.getSequenceNumber(),
                        previousFileDescriptor.getLowerBound(),
                        previousFileDescriptor.getUpperBound(),
                        previousFileDescriptor.getOrigin(),
                        previousFileDescriptor.getTimestamp(),
                        currentFileDescriptor));
    }

    @Test
    @DisplayName("Decreasing origin")
    void decreasingOrigin() {
        final PcesFile previousFileDescriptor = PcesFile.of(time.now(), 2, 10, 20, 5, Path.of("root"));
        final PcesFile currentFileDescriptor = PcesFile.of(
                time.now(),
                previousFileDescriptor.getSequenceNumber() + 1,
                previousFileDescriptor.getLowerBound(),
                previousFileDescriptor.getUpperBound(),
                previousFileDescriptor.getOrigin() - 1,
                Path.of("root"));

        assertThrows(
                IllegalStateException.class,
                () -> PcesUtilities.fileSanityChecks(
                        false,
                        previousFileDescriptor.getSequenceNumber(),
                        previousFileDescriptor.getLowerBound(),
                        previousFileDescriptor.getUpperBound(),
                        previousFileDescriptor.getOrigin(),
                        previousFileDescriptor.getTimestamp(),
                        currentFileDescriptor));
    }
}
