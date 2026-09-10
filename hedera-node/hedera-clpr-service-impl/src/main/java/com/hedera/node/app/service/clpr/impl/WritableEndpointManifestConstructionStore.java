// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.clpr.impl;

import static com.hedera.node.app.service.clpr.impl.schemas.V0770ClprSchema.ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.clpr.ClprEndpointManifestConstruction;
import com.hedera.hapi.node.state.clpr.ClprEndpointPublication;
import com.hedera.hapi.node.state.clpr.ClprEndpointPublicationEntry;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.ArrayList;
import java.util.Comparator;

/**
 * Writable view of the endpoint-manifest construction singleton. The construction is
 * transient: present while gathering, cleared on close (advance or no-op).
 */
public class WritableEndpointManifestConstructionStore extends ReadableEndpointManifestConstructionStoreImpl {

    private final WritableStates states;

    public WritableEndpointManifestConstructionStore(@NonNull final WritableStates states) {
        super(states);
        this.states = requireNonNull(states);
    }

    /**
     * Persist the given construction, replacing any prior value in state.
     */
    public void put(@NonNull final ClprEndpointManifestConstruction construction) {
        requireNonNull(construction);
        states.<ClprEndpointManifestConstruction>getSingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID)
                .put(construction);
    }

    /**
     * Clear the construction singleton. Called by the reconciler when a construction closes.
     *
     * <p>Stores {@link ClprEndpointManifestConstruction#DEFAULT} rather than {@code null}: a {@code null} put is not
     * captured as a state change (so it is not externalized to the block stream). Readers translate {@code DEFAULT}
     * back to {@code null} — see {@link ReadableEndpointManifestConstructionStoreImpl#get()} — so callers still see
     * "no construction in flight".
     */
    public void clear() {
        states.<ClprEndpointManifestConstruction>getSingleton(ENDPOINT_MANIFEST_CONSTRUCTION_STATE_ID)
                .put(ClprEndpointManifestConstruction.DEFAULT);
    }

    /**
     * Admit a publication into the active construction. Returns {@code true} if accepted,
     * {@code false} if the publication was dropped because:
     * <ul>
     *   <li>no construction is in flight, or</li>
     *   <li>the publisher is not in the current construction's target set.</li>
     * </ul>
     *
     * <p>Publisher identity is the platform-authoritative {@code creatorInfo().nodeId()}, so a node
     * cannot publish on another's behalf; Publications are matched to the manifest by full-value
     * membership, not by account).
     *
     * <p>On acceptance, the construction singleton is mutated in place with the new entry appended
     * (or an existing entry for the same node id replaced — last-write-wins per design §7.2).
     * Entries are kept sorted ascending by {@code node_id} on write.
     *
     * @param publisherNodeId platform-authoritative publisher (creatorInfo().nodeId())
     * @param publication the payload
     */
    public boolean admitPublication(final long publisherNodeId, @NonNull final ClprEndpointPublication publication) {
        requireNonNull(publication);
        final var construction = get();
        if (construction == null) {
            return false;
        }
        if (!construction.targetNodeIds().contains(publisherNodeId)) {
            return false;
        }
        final var entry = ClprEndpointPublicationEntry.newBuilder()
                .nodeId(publisherNodeId)
                .publication(publication)
                .build();
        final var updated = new ArrayList<>(construction.gatheredPublications());
        updated.removeIf(e -> e.nodeId() == publisherNodeId); // drop any previous entry for this node (last-write-wins)
        updated.add(entry);
        updated.sort(Comparator.comparingLong(ClprEndpointPublicationEntry::nodeId));
        put(construction.copyBuilder().gatheredPublications(updated).build());
        return true;
    }
}
