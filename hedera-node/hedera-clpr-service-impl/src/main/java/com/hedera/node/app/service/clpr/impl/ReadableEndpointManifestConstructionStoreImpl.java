// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.node.app.service.clpr.ReadableEndpointManifestConstructionStore;
import com.swirlds.state.spi.ReadableSingletonState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;

/**
 * Read-side view of the endpoint-manifest construction singleton.
 */
public class ReadableEndpointManifestConstructionStoreImpl implements ReadableEndpointManifestConstructionStore {

    private final ReadableSingletonState<ClprEndpointManifestConstruction> constructionState;

    public ReadableEndpointManifestConstructionStoreImpl(@NonNull final ReadableStates states) {
        requireNonNull(states);
        this.constructionState = states.getSingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID);
    }

    @Override
    @Nullable
    public ClprEndpointManifestConstruction get() {
        final ClprEndpointManifestConstruction value = constructionState.get();
        // A cleared construction is stored as DEFAULT (so the clear is externalized to the block stream) rather than
        // null; surface both as "no construction in flight" to callers, whose logic keys on a null result.
        return value == null || ClprEndpointManifestConstruction.DEFAULT.equals(value) ? null : value;
    }
}
