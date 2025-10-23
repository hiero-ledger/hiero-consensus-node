// SPDX-License-Identifier: Apache-2.0
package com.swirlds.state.lifecycle;

import com.swirlds.config.api.Configuration;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Comparator;
import java.util.Map;

/**
 * Provides the context for a migration of state from one {@link Schema} version to another.
 *
 * @param <V> - version type
 */
public interface MigrationContext<V> {
    /**
     * Returns the round number of the state being migrated, zero at genesis.
     */
    long roundNumber();

    /**
     * Provides a reference to the previous {@link ReadableStates}. For example, if the previous state was version
     * 1.2.3, then this method will return a {@link ReadableStates} that can be used to read the state of version 1.2.3.
     * This state is strictly read-only. This is useful as it allows the migration code to refer to the previous state.
     *
     * @return A non-null reference to the previous states. For Genesis, this will be an empty {@link ReadableStates}.
     */
    @NonNull
    ReadableStates previousStates();

    /**
     * Provides a references to the current working state. Initially, this state will be identical to that returned by
     * {@link #previousStates()}, but as the migration progresses, this state will be updated to reflect the new values
     * of the state. All new {@link Schema#statesToCreate()} will exist in this state.
     *
     * @return A non-null reference to the working state.
     */
    @NonNull
    WritableStates newStates();

    /**
     * The app {@link Configuration} for this migration. Any portion of this configuration which was based on state
     * (such as, in our case, file 121) will be current as of the previous state. This configuration is read-only.
     * Having this configuration is useful for migrations that should behavior differently based on configuration.
     *
     * @return The application configuration to use.
     */
    @NonNull
    Configuration appConfig();

    /**
     * The platform {@link Configuration} for this migration.
     *
     * @return The platform configuration to use
     */
    @NonNull
    Configuration platformConfig();

    /**
     * Copies and releases the underlying on-disk state for the given ID. If this is not called
     * periodically during a large migration, the underlying {@code VirtualMap} will grow too large
     * and apply extreme backpressure during transaction handling post-migration.
     *
     * @param stateId the ID of the state to copy and release
     */
    void copyAndReleaseOnDiskState(int stateId);

    /**
     * Provides the previous version of the schema. This is useful to know if this is genesis restart
     * @return the previous version of the schema. Previous version will be null if this is genesis restart
     */
    @Nullable
    V previousVersion();

    /**
     * Returns a mutable "scratchpad" that can be used to share values between different services
     * during a migration.
     *
     * @return the shared values map
     */
    Map<String, Object> sharedValues();

    /**
     * Returns whether this is a genesis migration.
     */
    default boolean isGenesis() {
        return previousVersion() == null;
    }

    /**
     * Provides a comparator for comparing versions of type {@code V}.
     *
     * @return a comparator for ordering or comparing versions of type {@code V}
     */
    Comparator<V> getVersionComparator();

    /**
     * Returns whether the current version is an upgrade from the previous version, relative to the ordering
     * implied by the given functions used to compare the version in the current app configuration and the
     * previous state version.
     * @param currentVersion the function to compute the current version from the app configuration
     * @return whether the current version is an upgrade from the previous version
     * @param <T> the type of the version
     */
    default <T extends Comparable<? super T>> boolean isUpgrade(@NonNull final V currentVersion) {
        return getVersionComparator().compare(currentVersion, previousVersion()) > 0;
    }
}
