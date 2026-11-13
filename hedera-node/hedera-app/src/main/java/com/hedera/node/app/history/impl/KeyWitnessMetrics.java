// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.history.impl;

import static java.util.Objects.requireNonNull;

import com.swirlds.metrics.api.LongGauge;
import com.swirlds.metrics.api.Metrics;
import edu.umd.cs.findbugs.annotations.NonNull;

/** Observability for durable witness publication when real hinTS signing is enabled. */
final class KeyWitnessMetrics {
    private final LongGauge pendingCount;
    private final LongGauge oldestPendingAgeSeconds;

    KeyWitnessMetrics(@NonNull final Metrics metrics) {
        requireNonNull(metrics);
        pendingCount = metrics.getOrCreate(new LongGauge.Config("app", "hintsKeyWitnessPendingCount")
                .withDescription("Adopted hinTS generations missing a witness; zero while real signing is disabled"));
        oldestPendingAgeSeconds = metrics.getOrCreate(new LongGauge.Config(
                        "app", "hintsKeyWitnessOldestPendingAgeSeconds")
                .withDescription("Oldest known activation age of a pending witness; excludes legacy unknown boundaries")
                .withUnit("seconds"));
    }

    void update(final int pending, final long ageSeconds) {
        pendingCount.set(pending);
        oldestPendingAgeSeconds.set(ageSeconds);
    }
}
