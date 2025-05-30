// SPDX-License-Identifier: Apache-2.0
package com.swirlds.platform.event.preconsensus;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.swirlds.base.test.fixtures.time.FakeTime;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.time.Duration;
import org.hiero.consensus.model.event.AncientMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

    @ParameterizedTest
    @EnumSource(AncientMode.class)
    @DisplayName("Standard operation")
    void standardOperation(@NonNull final AncientMode ancientMode) {
        final PcesFile previousFileDescriptor = PcesFile.of(ancientMode, time.now(), 2, 10, 20, 5, Path.of("root"));
        final PcesFile currentFileDescriptor = PcesFile.of(
                ancientMode,
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

    @ParameterizedTest
    @EnumSource(AncientMode.class)
    @DisplayName("Decreasing sequence number")
    void decreasingSequenceNumber(@NonNull final AncientMode ancientMode) {
        final PcesFile previousFileDescriptor = PcesFile.of(ancientMode, time.now(), 2, 10, 20, 5, Path.of("root"));
        final PcesFile currentFileDescriptor = PcesFile.of(
                ancientMode,
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

    @ParameterizedTest
    @EnumSource(AncientMode.class)
    @DisplayName("Decreasing sequence number with gaps permitted")
    void decreasingSequenceNumberWithGapsPermitted(@NonNull final AncientMode ancientMode) {
        final PcesFile previousFileDescriptor = PcesFile.of(ancientMode, time.now(), 2, 10, 20, 5, Path.of("root"));
        final PcesFile currentFileDescriptor = PcesFile.of(
                ancientMode,
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

    @ParameterizedTest
    @EnumSource(AncientMode.class)
    @DisplayName("Non-increasing sequence number")
    void nonIncreasingSequenceNumber(@NonNull final AncientMode ancientMode) {
        final PcesFile previousFileDescriptor = PcesFile.of(ancientMode, time.now(), 2, 10, 20, 5, Path.of("root"));
        final PcesFile currentFileDescriptor = PcesFile.of(
                ancientMode,
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

    @ParameterizedTest
    @EnumSource(AncientMode.class)
    @DisplayName("Decreasing Lower Bound")
    void decreasingMinimumLowerBound(@NonNull final AncientMode ancientMode) {
        final PcesFile previousFileDescriptor = PcesFile.of(ancientMode, time.now(), 2, 10, 20, 5, Path.of("root"));
        final PcesFile currentFileDescriptor = PcesFile.of(
                ancientMode,
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

    @ParameterizedTest
    @EnumSource(AncientMode.class)
    @DisplayName("Decreasing Upper Bound")
    void decreasingUpperBound(@NonNull final AncientMode ancientMode) {
        final PcesFile previousFileDescriptor = PcesFile.of(ancientMode, time.now(), 2, 10, 20, 5, Path.of("root"));
        final PcesFile currentFileDescriptor = PcesFile.of(
                ancientMode,
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

    @ParameterizedTest
    @EnumSource(AncientMode.class)
    @DisplayName("Decreasing timestamp")
    void decreasingTimestamp(@NonNull final AncientMode ancientMode) {
        final PcesFile previousFileDescriptor = PcesFile.of(ancientMode, time.now(), 2, 10, 20, 5, Path.of("root"));
        final PcesFile currentFileDescriptor = PcesFile.of(
                ancientMode,
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

    @ParameterizedTest
    @EnumSource(AncientMode.class)
    @DisplayName("Decreasing origin")
    void decreasingOrigin(@NonNull final AncientMode ancientMode) {
        final PcesFile previousFileDescriptor = PcesFile.of(ancientMode, time.now(), 2, 10, 20, 5, Path.of("root"));
        final PcesFile currentFileDescriptor = PcesFile.of(
                ancientMode,
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
