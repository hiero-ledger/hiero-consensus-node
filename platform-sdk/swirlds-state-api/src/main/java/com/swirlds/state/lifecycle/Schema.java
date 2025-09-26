// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.lifecycle;

import com.hedera.hapi.node.base.SemanticVersion;
import com.hedera.hapi.util.HapiUtils;
import com.hedera.pbj.runtime.Codec;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * Defines the schema of all states for a specific {@link SemanticVersion} of a specific {@link
 * Service} instance. It is necessary to create a new {@link Schema}
 * whenever a new {@link ReadableKVState} is to be created, or an existing one removed, or a
 * migration has to happen. If your service makes use of a forwards and backwards compatible
 * serialization system (such as protobuf), then it is not necessary to define a new {@link Schema}
 * for simple compatible changes to serialization.
 */
public abstract class Schema implements Comparable<Schema> {
    /** The version of this schema */
    private final SemanticVersion version;

    /**
     * Create a new instance
     *
     * @param version The version of this schema
     */
    protected Schema(@NonNull final SemanticVersion version) {
        this.version = Objects.requireNonNull(version);
    }

    /**
     * Gets the version of this {@link Schema}
     *
     * @return The version
     */
    @NonNull
    public SemanticVersion getVersion() {
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
     * Called after all new states have been created (as per {@link #statesToCreate()}), this method
     * is used to perform all <b>synchronous</b> migrations of state. Only called at network genesis
     * or when restarting from a state whose version is strictly earlier than this schema's. The
     * {@link MigrationContext} will contain the {@link ReadableStates} from the saved state; or
     * there was no previous version, a non-null empty {@link ReadableStates}.
     *
     * @param ctx {@link MigrationContext} for this schema migration
     */
    public void migrate(@NonNull final MigrationContext ctx) {
        Objects.requireNonNull(ctx);
    }

    /**
     * The {@link Set} of state IDs of all states that should be removed <b>AFTER</b> {@link
     * #migrate(MigrationContext)}.
     *
     * @return the set of states to remove
     */
    @NonNull
    public Set<Integer> statesToRemove() {
        return Collections.emptySet();
    }

    /**
     * Called on this schema if and only if it is the most recent schema that is not newer
     * than the version of software in use when the node is restarted. A service might override
     * this if it uses views of state that need to be rebuilt on restart. This is not common,
     * but is provided for completeness.
     *
     * @param ctx {@link MigrationContext} for this schema restart operation
     */
    public void restart(@NonNull final MigrationContext ctx) {
        Objects.requireNonNull(ctx);
    }

    /** {@inheritDoc} */
    @Override
    public int compareTo(Schema o) {
        return HapiUtils.SEMANTIC_VERSION_COMPARATOR.compare(this.version, o.version);
    }

    /** {@inheritDoc} */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof Schema other)) {
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
