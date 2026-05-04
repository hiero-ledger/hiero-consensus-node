// SPDX-License-Identifier: Apache-2.0
package com.swirlds.benchmark.reconnect;

import com.swirlds.metrics.api.Metrics;
import java.util.Objects;

public record ReconnectMapStatsSnapshot(
        long transfersFromTeacher,
        long transfersFromLearner,
        long internalHashes,
        long internalCleanHashes,
        long internalData,
        long internalCleanData,
        long leafHashes,
        long leafCleanHashes,
        long leafData,
        long leafCleanData) {

    private static final String RECONNECT_MAP_CATEGORY = "reconnect_vmap";

    public static ReconnectMapStatsSnapshot from(final Metrics metrics) {
        Objects.requireNonNull(metrics, "metrics must not be null");
        return new ReconnectMapStatsSnapshot(
                read(metrics, "transfersFromTeacherTotal"),
                read(metrics, "transfersFromLearnerTotal"),
                read(metrics, "internalHashesTotal"),
                read(metrics, "internalCleanHashesTotal"),
                read(metrics, "internalDataTotal"),
                read(metrics, "internalCleanDataTotal"),
                read(metrics, "leafHashesTotal"),
                read(metrics, "leafCleanHashesTotal"),
                read(metrics, "leafDataTotal"),
                read(metrics, "leafCleanDataTotal"));
    }

    private static long read(final Metrics metrics, final String name) {
        final Object value = metrics.getValue(RECONNECT_MAP_CATEGORY, name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Missing numeric reconnect metric " + RECONNECT_MAP_CATEGORY + "." + name);
    }

    public String format() {
        return "ReconnectMapStatsSnapshot: "
                + "transfersFromTeacher=" + transfersFromTeacher
                + "; transfersFromLearner=" + transfersFromLearner
                + "; internalHashes=" + internalHashes
                + "; internalCleanHashes=" + internalCleanHashes
                + "; internalData=" + internalData
                + "; internalCleanData=" + internalCleanData
                + "; leafHashes=" + leafHashes
                + "; leafCleanHashes=" + leafCleanHashes
                + "; leafData=" + leafData
                + "; leafCleanData=" + leafCleanData;
    }
}
