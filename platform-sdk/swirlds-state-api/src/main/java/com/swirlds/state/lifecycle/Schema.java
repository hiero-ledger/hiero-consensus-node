// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.lifecycle;

import com.hedera.pbj.runtime.Codec;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;

/**
 * Defines the schema of all states for a specific version of a specific {@link
 * Service} instance. It is necessary to create a new {@link Schema}
 * whenever a new {@link ReadableKVState} is to be created, or an existing one removed. If your
 * service makes use of a forwards and backwards compatible serialization system (such as protobuf),
 * then it is not necessary to define a new {@link Schema} for simple compatible changes to
 * serialization.
 *
 * <p>A schema's version only orders it relative to the other schemas registered by the same
 * service; it has no relationship to the software version. Every registered schema is applied,
 * in version order, each time a state is initialized. State that must hold a value at genesis is
 * written by {@link Service#doGenesisSetup}; any other startup-time work belongs in the
 * {@link #restart(MigrationContext)} of the service's latest schema.
 * @param <V> - version type
 */
public abstract class Schema<V> implements Comparable<Schema<V>> {
    /** The version of this schema */
    private final V version;

    private final Comparator<V> versionComparator;

    /**
     * Create a new instance
     *
     * @param version The version of this schema
     */
    protected Schema(@NonNull final V version, @NonNull Comparator<V> versionComparator) {
        this.version = Objects.requireNonNull(version);
        this.versionComparator = Objects.requireNonNull(versionComparator);
    }

    /**
     * Gets the version of this {@link Schema}
     *
     * @return The version
     */
    @NonNull
    public V getVersion() {
        return version;
    }

    /**
     * Gets a {@link Set} of state definitions for states to create in this schema. For example,
     * perhaps in this version of the schema, you need to create a new state FOO. The set will have
     * a {@link StateDefinition} specifying the metadata for that state.
     *
     * <p>If a state is defined in this set that already exists, it will be redefined based on the
     * returned values. This can be used if a state exists but needs to use a new {@link Codec}, for
     * example.
     *
     * @return A map of all states to be created. Possibly empty.
     */
    @NonNull
    @SuppressWarnings("rawtypes")
    public Set<StateDefinition> statesToCreate() {
        return Collections.emptySet();
    }

    /**
     * A variant of {@link #statesToCreate()} that allows for the {@link Configuration} to be
     * consulted when determining which states to create. This is useful if the schema needs to
     * create different states based on the configuration.
     *
     * @param configuration The configuration to consult
     * @return A map of all states to be created. Possibly empty.
     */
    @NonNull
    @SuppressWarnings("rawtypes")
    public Set<StateDefinition> statesToCreate(@NonNull final Configuration configuration) {
        return statesToCreate();
    }

    /**
     * The {@link Set} of state IDs of all states that should be removed <b>AFTER</b> this schema's
     * states are created and, if it is its service's latest schema, its
     * {@link #restart(MigrationContext)} has run.
     *
     * @return the set of states to remove
     */
    @NonNull
    public Set<Integer> statesToRemove() {
        return Collections.emptySet();
    }

    /**
     * Called on this schema if and only if it is the latest schema registered by its service, each
     * time a state is initialized (at genesis, and on every restart and reconnect). The
     * {@link MigrationContext} will contain the {@link ReadableStates} as they were before this
     * schema's states were created and removed.
     *
     * <p>Since this runs on every initialization, anything it writes must be a deterministic,
     * idempotent function of the state and configuration (in particular, a no-op when it runs on a
     * state that already reflects it, such as a state learned during reconnect). Genesis values
     * belong in {@link Service#doGenesisSetup} instead, which is why most overrides skip the
     * {@link MigrationContext#isGenesis()} case.
     *
     * @param ctx {@link MigrationContext} for this schema restart operation
     */
    public void restart(@NonNull final MigrationContext<V> ctx) {
        Objects.requireNonNull(ctx);
    }

    /** {@inheritDoc} */
    @Override
    public int compareTo(Schema<V> o) {
        return versionComparator.compare(version, o.version);
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof Schema<?> other)) {
            return false;
        }

        return version.equals(other.version);
    }

    /** {@inheritDoc} */
    @Override
    public int hashCode() {
        return version.hashCode();
    }
}
