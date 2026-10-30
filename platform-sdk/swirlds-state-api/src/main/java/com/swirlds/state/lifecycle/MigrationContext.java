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
 * Provides the context for a service's latest {@link Schema#restart(MigrationContext)} hook
 * when its state is initialized.
 *
 * @param <V> - version type
 */
public interface MigrationContext<V> {
    /**
     * Returns the round number of the state being migrated, zero at genesis.
     */
    long roundNumber();

    /**
     * Provides a read-only view of the service states available before this schema's state definitions
     * were applied. Earlier schemas' state definitions have already been applied, including at genesis.
     *
     * @return A non-null reference to the previous states.
     */
    @NonNull
    ReadableStates previousStates();

    /**
     * Provides a reference to the current working state after this schema's state definitions have been
     * applied. This includes the new {@link Schema#statesToCreate()} and excludes the states listed in
     * {@link Schema#statesToRemove()}.
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
     * Provides the software version associated with the loaded state, independent of schema versions.
     *
     * @return the previous software version, or null at genesis
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
     * Returns whether the current software version is an upgrade from the previous software version,
     * using {@link #getVersionComparator()}.
     * @param currentVersion the current software version
     * @return whether the current software version is an upgrade from the previous software version
     * @param <T> the type of the version
     */
    default <T extends Comparable<? super T>> boolean isUpgrade(@NonNull final V currentVersion) {
        return getVersionComparator().compare(currentVersion, previousVersion()) > 0;
    }
}
