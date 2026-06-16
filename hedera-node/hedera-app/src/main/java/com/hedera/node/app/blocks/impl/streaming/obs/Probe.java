// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.blocks.impl.streaming.obs;

import static java.util.Objects.requireNonNull;

import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Base class for metric accumulators.
 *
 * <p>Owns the name/unit identity, the sealed-after-aggregate contract (enforced in the
 * {@code final} {@link #add} method via the template-method hooks {@link #isAggregated()} and
 * {@link #doAdd}), and the shared {@link #toString()} format.
 *
 * <p>Subclasses choose their accumulation strategy by implementing {@link #doAdd},
 * {@link #isAggregated}, {@link #statistics}, and {@link #aggregate}.
 */
public abstract class Probe {

    private final String name;
    private final ObsUnit unit;

    /**
     * @param name the probe name, used as the label in {@link #toString()}
     * @param unit the unit of the values recorded by this probe
     */
    protected Probe(@NonNull final String name, @NonNull final ObsUnit unit) {
        this.name = requireNonNull(name);
        this.unit = requireNonNull(unit);
    }

    /**
     * @return the probe name
     */
    public @NonNull String name() {
        return name;
    }

    /**
     * @return the unit of the values recorded by this probe
     */
    public @NonNull ObsUnit unit() {
        return unit;
    }

    /**
     * Records a single value.
     *
     * @param value the value to record
     * @throws IllegalStateException if {@link #aggregate()} has already been called
     */
    public final void add(final long value) {
        if (isAggregated()) {
            throw new IllegalStateException("Probe is already aggregated; cannot add more values");
        }
        doAdd(value);
    }

    /**
     * @return the aggregated statistics, or {@code null} if {@link #aggregate()} has not yet been called
     */
    public abstract @Nullable Statistics statistics();

    /**
     * Seals the probe and computes the final statistics. Idempotent after the first call.
     *
     * @return the aggregated statistics for this probe
     */
    public abstract @NonNull Statistics aggregate();

    /**
     * Called by {@link #add} after the sealed-check passes. Subclasses record the value here.
     *
     * @param value the value to record
     */
    protected abstract void doAdd(long value);

    /**
     * @return {@code true} once {@link #aggregate()} has been called and the probe is sealed
     */
    protected abstract boolean isAggregated();

    /**
     * @return the probe name followed by its statistics, or a {@code <In Progress>} marker if
     * {@link #aggregate()} has not yet been called
     */
    @Override
    public String toString() {
        final Statistics stats = statistics();
        return name + " " + (stats == null ? "{ <In Progress> }" : Statistics.toString(stats));
    }
}
