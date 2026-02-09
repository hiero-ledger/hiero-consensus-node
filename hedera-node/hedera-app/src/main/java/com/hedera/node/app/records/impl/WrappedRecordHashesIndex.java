// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.records.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Tracks the block numbers present in the wrapped record hashes file and detects gaps between
 * the lowest and highest observed block numbers.
 */
final class WrappedRecordHashesIndex {
    record GapRange(long startInclusive, long endInclusive) {}

    private long lowest = -1;
    private long highest = -1;
    private final TreeMap<Long, Long> intervals = new TreeMap<>();
    private final Set<GapRange> loggedGaps = new HashSet<>(8);

    long lowestBlock() {
        return lowest;
    }

    long highestBlock() {
        return highest;
    }

    boolean hasGaps() {
        return intervals.size() > 1;
    }

    void reset() {
        lowest = -1;
        highest = -1;
        intervals.clear();
        loggedGaps.clear();
    }

    /**
     * Returns {@code true} if the given block number is already present in this index.
     */
    boolean contains(final long blockNumber) {
        if (blockNumber < 0) return false;
        final var floor = intervals.floorEntry(blockNumber);
        return floor != null && floor.getValue() >= blockNumber;
    }

    void add(final long blockNumber) {
        addIntervalPoint(blockNumber);
    }

    List<GapRange> addAndGetNewGaps(final long blockNumber) {
        addIntervalPoint(blockNumber);
        final var allGaps = computeGaps();
        final var newOnes = new ArrayList<GapRange>();
        for (final var gap : allGaps) {
            if (loggedGaps.add(gap)) {
                newOnes.add(gap);
            }
        }
        return newOnes;
    }

    private void addIntervalPoint(final long b) {
        if (b < 0) return;

        // Already covered?
        final var floor = intervals.floorEntry(b);
        if (floor != null && floor.getValue() >= b) {
            lowest = lowest == -1 ? floor.getKey() : Math.min(lowest, floor.getKey());
            highest = Math.max(highest, floor.getValue());
            return;
        }

        long start = b;
        long end = b;

        // Merge with left-adjacent interval
        if (floor != null && floor.getValue() + 1 == b) {
            start = floor.getKey();
            intervals.remove(floor.getKey());
        }

        // Merge with right-adjacent (and any subsequent) intervals
        var ceiling = intervals.ceilingEntry(b);
        while (ceiling != null && ceiling.getKey() - 1 <= end) {
            end = Math.max(end, ceiling.getValue());
            intervals.remove(ceiling.getKey());
            ceiling = intervals.ceilingEntry(b);
        }

        intervals.put(start, end);

        lowest = (lowest == -1) ? start : Math.min(lowest, start);
        highest = Math.max(highest, end);
    }

    private List<GapRange> computeGaps() {
        if (intervals.size() <= 1) return List.of();

        final var gaps = new ArrayList<GapRange>();
        Long prevEnd = null;
        for (final var e : intervals.entrySet()) {
            if (prevEnd != null) {
                final long gapStart = prevEnd + 1;
                final long gapEnd = e.getKey() - 1;
                if (gapStart <= gapEnd) {
                    gaps.add(new GapRange(gapStart, gapEnd));
                }
            }
            prevEnd = e.getValue();
        }
        return gaps;
    }
}
