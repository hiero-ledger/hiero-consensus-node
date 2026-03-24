// SPDX-License-Identifier: Apache-2.0
package com.swirlds.merkledb.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("GarbageFileStats ratio calculations")
class GarbageFileStatsTest {

    @Test
    @DisplayName("liveToDeadRatio: normal case with both alive and dead items")
    void normalLiveToDeadRatio() {
        // 40 alive out of 100 total → 40 live / 60 dead = 0.667
        final var stats = statsWithAlive(100, 40);
        assertEquals(40.0 / 60.0, stats.liveToDeadRatio(), 1e-9);
    }

    @Test
    @DisplayName("liveToDeadRatio: all items alive (zero dead) → MAX_VALUE")
    void allAliveRatio() {
        final var stats = statsWithAlive(50, 50);
        assertEquals(Double.MAX_VALUE, stats.liveToDeadRatio());
    }

    @Test
    @DisplayName("liveToDeadRatio: all items dead (zero alive) → 0.0")
    void allDeadRatio() {
        final var stats = statsWithAlive(50, 0);
        assertEquals(0.0, stats.liveToDeadRatio());
    }

    @Test
    @DisplayName("liveToDeadRatio: totalItems == 0 → 0.0")
    void emptyFileRatio() {
        final var stats = statsWithAlive(0, 0);
        assertEquals(0.0, stats.liveToDeadRatio());
    }

    @Test
    @DisplayName("liveToDeadRatio: high garbage file (90% dead)")
    void highGarbageRatio() {
        // 10 alive out of 100 → 10 / 90 ≈ 0.111
        final var stats = statsWithAlive(100, 10);
        assertEquals(10.0 / 90.0, stats.liveToDeadRatio(), 1e-9);
    }

    @Test
    @DisplayName("liveToDeadRatio: exactly half alive")
    void halfAliveRatio() {
        final var stats = statsWithAlive(100, 50);
        assertEquals(1.0, stats.liveToDeadRatio(), 1e-9);
    }

    // ========================================================================
    // garbageRatio tests (still needed for size estimation)
    // ========================================================================

    @Test
    @DisplayName("garbageRatio: normal case")
    void normalGarbageRatio() {
        // 40 alive out of 100 → garbage = 60%
        final var stats = statsWithAlive(100, 40);
        assertEquals(0.6, stats.garbageRatio(), 1e-9);
    }

    @Test
    @DisplayName("garbageRatio: all alive → 0.0")
    void allAliveGarbageRatio() {
        final var stats = statsWithAlive(50, 50);
        assertEquals(0.0, stats.garbageRatio(), 1e-9);
    }

    @Test
    @DisplayName("garbageRatio: all dead → 1.0")
    void allDeadGarbageRatio() {
        final var stats = statsWithAlive(50, 0);
        assertEquals(1.0, stats.garbageRatio(), 1e-9);
    }

    @Test
    @DisplayName("garbageRatio: totalItems == 0 → 1.0")
    void emptyFileGarbageRatio() {
        final var stats = statsWithAlive(0, 0);
        assertEquals(1.0, stats.garbageRatio(), 1e-9);
    }

    // ========================================================================
    // deadItems tests
    // ========================================================================

    @Test
    @DisplayName("deadItems: computed correctly from totalItems - aliveItems")
    void deadItemsComputed() {
        final var stats = statsWithAlive(100, 35);
        assertEquals(65, stats.deadItems());
    }

    @Test
    @DisplayName("deadItems: zero when all alive")
    void deadItemsZero() {
        final var stats = statsWithAlive(100, 100);
        assertEquals(0, stats.deadItems());
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private static GarbageScanner.GarbageFileStats statsWithAlive(long totalItems, long aliveItems) {
        final DataFileMetadata metadata = mock(DataFileMetadata.class);
        when(metadata.getCompactionLevel()).thenReturn(0);
        when(metadata.getItemsCount()).thenReturn(totalItems);

        final DataFileReader reader = mock(DataFileReader.class);
        when(reader.getIndex()).thenReturn(1);
        when(reader.getMetadata()).thenReturn(metadata);

        return new GarbageScanner.GarbageFileStats(reader, aliveItems);
    }
}
