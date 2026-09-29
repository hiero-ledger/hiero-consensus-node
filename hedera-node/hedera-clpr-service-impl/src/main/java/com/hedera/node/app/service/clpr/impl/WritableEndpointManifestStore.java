// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.ENDPOINT_MANIFEST_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifest;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Provides write access to the CLPR endpoint manifest singleton in state.
 */
public class WritableEndpointManifestStore extends ReadableEndpointManifestStoreImpl {

    /** The underlying writable state. */
    private final WritableStates states;

    /**
     * Create a new {@link WritableEndpointManifestStore} instance.
     *
     * @param states the writable state to use
     */
    public WritableEndpointManifestStore(@NonNull final WritableStates states) {
        super(states);
        this.states = requireNonNull(states);
    }

    /**
     * Persist a {@link ClprEndpointManifest} into state.
     *
     * @param manifest the manifest to persist
     */
    public void put(@NonNull final ClprEndpointManifest manifest) {
        requireNonNull(manifest);
        final var singleton = states.<ClprEndpointManifest>getSingleton(ENDPOINT_MANIFEST_STATE_ID);
        singleton.put(manifest);
    }
}
