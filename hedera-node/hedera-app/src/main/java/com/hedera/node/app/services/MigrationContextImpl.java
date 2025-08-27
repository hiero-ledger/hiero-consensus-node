// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.services;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.SemanticVersion;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.StartupNetworks;
import com.swirlds.state.merkle.VirtualMapState.MerkleWritableStates;
import com.swirlds.state.spi.FilteredWritableStates;
import com.swirlds.state.spi.ReadableStates;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Map;

/**
 * An implementation of {@link MigrationContext}.
 *
 * @param previousStates The previous states.
 * @param newStates The new states, preloaded with any new state definitions.
 * @param appConfig The configuration to use
 * @param previousVersion the previous version of the state
 */
public record MigrationContextImpl(
        @NonNull ReadableStates previousStates,
        @NonNull WritableStates newStates,
        @NonNull Configuration appConfig,
        @NonNull Configuration platformConfig,
        @Nullable SemanticVersion previousVersion,
        long roundNumber,
        @NonNull Map<String, Object> sharedValues,
        @NonNull StartupNetworks startupNetworks)
        implements MigrationContext {
    public MigrationContextImpl {
        requireNonNull(previousStates);
        requireNonNull(newStates);
        requireNonNull(appConfig);
        requireNonNull(platformConfig);
    }

    @Override
    public void copyAndReleaseOnDiskState(@NonNull final String stateKey) {
        requireNonNull(stateKey);
        if (newStates instanceof MerkleWritableStates merkleWritableStates) {
            merkleWritableStates.copyAndReleaseVirtualMap(stateKey);
        } else if (newStates instanceof FilteredWritableStates filteredWritableStates
                && filteredWritableStates.getDelegate() instanceof MerkleWritableStates merkleWritableStates) {
            merkleWritableStates.copyAndReleaseVirtualMap(stateKey);
        } else {
            throw new UnsupportedOperationException("On-disk state is inaccessible");
        }
    }
}
