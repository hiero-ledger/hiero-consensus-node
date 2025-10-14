// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BlockNodeUtils {

    private BlockNodeUtils() {
        // no-op
    }

    public static String getContiguousRangesAsString(final List<Long> keys) {
        // Collect and sort keys
        Collections.sort(keys);

        if (keys.isEmpty()) {
            return "[]";
        }

        final List<String> ranges = new ArrayList<>();
        long start = keys.getFirst();
        long prev = start;

        for (int i = 1; i < keys.size(); i++) {
            final long current = keys.get(i);
            if (current != prev + 1) {
                // Close previous range
                ranges.add(formatRange(start, prev));
                start = current;
            }
            prev = current;
        }
        // Add last range
        ranges.add(formatRange(start, prev));

        return "[" + String.join(",", ranges) + "]";
    }

    private static String formatRange(final long start, final long end) {
        return start == end ? "(" + start + ")" : "(" + start + "-" + end + ")";
    }
}
