/*
 * Copyright (C) 2023-2025 Hedera Hashgraph, LLC
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

package com.swirlds.state.lifecycle;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;
import java.util.function.Function;

/**
 * Provides the context for a migration of state from one {@link Schema} version to another.
 */
public interface MigrationContext {
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
     * Information about the network itself. Generally, this is not useful information for migrations, but is used at
     * genesis for the file service. In the future, this may no longer be required.
     *
     * @return The {@link NetworkInfo} of the network at the time of migration.
     */
    @Nullable
    NetworkInfo genesisNetworkInfo();

    /**
     * Returns the startup networks in use.
     */
    @NonNull
    StartupNetworks startupNetworks();

    /**
     * Returns the startup networks in use.
     */
    @NonNull
    EntityIdFactory entityIdFactory();

    /**
     * Consumes and returns the next entity number. For use by migrations that need to create entities.
     * @return the next entity number
     */
    long newEntityNumForAccount();

    /**
     * Copies and releases the underlying on-disk state for the given key. If this is not called
     * periodically during a large migration, the underlying {@code VirtualMap} will grow too large
     * and apply extreme backpressure during transaction handling post-migration.
     *
     * @param stateKey the key of the state to copy and release
     */
    void copyAndReleaseOnDiskState(String stateKey);

    /**
     * Provides the previous version of the schema. This is useful to know if this is genesis restart
     * @return the previous version of the schema. Previous version will be null if this is genesis restart
     */
    @Nullable
    SemanticVersion previousVersion();

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
        return previousVersion() == null || previousVersion() == SemanticVersion.DEFAULT;
    }

    /**
     * Returns whether the current version is an upgrade from the previous version, relative to the ordering
     * implied by the given functions used to compare the version in the current app configuration and the
     * previous state version.
     * @param currentVersionFn the function to compute the current version from the app configuration
     * @param previousVersionFn the function to compute the previous version from the saved state
     * @return whether the current version is an upgrade from the previous version
     * @param <T> the type of the version
     */
    default <T extends Comparable<? super T>> boolean isUpgrade(
            @NonNull final Function<Configuration, T> currentVersionFn,
            @NonNull final Function<SemanticVersion, T> previousVersionFn) {
        final var current = currentVersionFn.apply(appConfig());
        final var previous = previousVersion();
        return currentVersionFn.apply(appConfig()).compareTo(previousVersionFn.apply(previousVersion())) > 0;
    }
}
