// SPDX-License-Identifier: Apache-2.0
package com.hedera.services.bdd.junit.support.validators.block;

import java.util.Map;
import java.util.TreeMap;

final class BinaryStateChangeSummary {
    private final Map<Integer, StateChangeCounts> summaries = new TreeMap<>();

    void countSingletonPut(final int stateId) {
        summaries.computeIfAbsent(stateId, ignore -> new StateChangeCounts()).singletonPuts++;
    }

    void countMapUpdate(final int stateId) {
        summaries.computeIfAbsent(stateId, ignore -> new StateChangeCounts()).mapUpdates++;
    }

    void countMapDelete(final int stateId) {
        summaries.computeIfAbsent(stateId, ignore -> new StateChangeCounts()).mapDeletes++;
    }

    void countQueuePush(final int stateId) {
        summaries.computeIfAbsent(stateId, ignore -> new StateChangeCounts()).queuePushes++;
    }

    void countQueuePop(final int stateId) {
        summaries.computeIfAbsent(stateId, ignore -> new StateChangeCounts()).queuePops++;
    }

    @Override
    public String toString() {
        final var sb = new StringBuilder();
        summaries.forEach((stateId, counts) ->
                sb.append("- ").append(stateId).append(" - ").append(counts).append('\n'));
        return sb.toString();
    }

    private static final class StateChangeCounts {
        private long singletonPuts;
        private long mapUpdates;
        private long mapDeletes;
        private long queuePushes;
        private long queuePops;

        @Override
        public String toString() {
            final var parts = new StringBuilder();
            if (singletonPuts > 0) {
                parts.append("singleton puts=").append(singletonPuts).append(' ');
            }
            if (mapUpdates > 0 || mapDeletes > 0) {
                parts.append("map updates=")
                        .append(mapUpdates)
                        .append(", deletes=")
                        .append(mapDeletes)
                        .append(' ');
            }
            if (queuePushes > 0 || queuePops > 0) {
                parts.append("queue pushes=")
                        .append(queuePushes)
                        .append(", pops=")
                        .append(queuePops);
            }
            return parts.toString().trim();
        }
    }
}
