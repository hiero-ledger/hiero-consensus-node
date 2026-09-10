// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.hedera.node.app.service.clpr.ReadableEndpointManifestStore;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides read-only access to the CLPR endpoint manifest singleton in state.
 */
public class ReadableEndpointManifestStoreImpl implements ReadableEndpointManifestStore {

    /** The underlying singleton state holding the manifest. */
    private final ReadableSingletonState<ClprEndpointManifest> manifestState;

    /**
     * Create a new {@link ReadableEndpointManifestStoreImpl} instance.
     *
     * @param states the state to use
     */
    public ReadableEndpointManifestStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        this.manifestState = states.getSingleton(ENDPOINT_MANIFEST_STATE_ID);
    }

    @Override
    @NonNull
    public ClprEndpointManifest get() {
        return requireNonNull(manifestState.get());
    }
}
