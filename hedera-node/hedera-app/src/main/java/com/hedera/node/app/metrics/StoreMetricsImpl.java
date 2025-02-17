/*
 * Copyright (C) 2024-2025 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hedera.node.app.metrics;

import static java.util.Objects.requireNonNull;

import com.google.common.annotations.VisibleForTesting;
import com.swirlds.common.metrics.FunctionGauge.Config;
import com.swirlds.metrics.api.Metrics;
import com.swirlds.state.spi.metrics.StoreMetrics;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Helper class that maintains utilization metrics for a store.
 */
public class StoreMetricsImpl implements StoreMetrics {
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong capacity = new AtomicLong(1L);

    public StoreMetricsImpl(@NonNull final Metrics metrics, @NonNull final String name) {
        this(metrics, name, name);
    }

    /**
     * Create a new {@link StoreMetricsImpl} instance.
     *
     * @param metrics The metrics-API used to report utilization.
     * @param name The name of the entities that are being tracked.
     * @param label The label of the entities used in the description.
     */
    public StoreMetricsImpl(@NonNull final Metrics metrics, @NonNull final String name, @NonNull final String label) {
        requireNonNull(name, "name must not be null");
        requireNonNull(label, "label must not be null");

        final var totalUtilizationConfig = new Config<>("app", name + "Used", Long.class, count::get)
                .withDescription(String.format("instantaneous %% used of %s system limit", label))
                .withFormat("%,d");
        metrics.getOrCreate(totalUtilizationConfig);

        final var relativeUtilizationConfig = new Config<>(
                        "app",
                        name + "PercentUsed",
                        Double.class,
                        () -> 100.0 * count.get() / Math.max(1L, capacity.get()))
                .withDescription(String.format("instantaneous %% used of %s system limit", label))
                .withFormat("%,13.2f");
        metrics.getOrCreate(relativeUtilizationConfig);
    }

    /**
     * Update the metrics with the current count.
     *
     * @param newValue The current count.
     */
    @Override
    public void updateCount(final long newValue) {
        count.set(newValue);
    }

    void updateCapacity(final long newValue) {
        capacity.set(newValue);
    }

    @VisibleForTesting
    public AtomicLong getCount() {
        return count;
    }
}
