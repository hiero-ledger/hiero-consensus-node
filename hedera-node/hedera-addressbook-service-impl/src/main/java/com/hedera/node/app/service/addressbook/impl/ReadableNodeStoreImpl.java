// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl;

import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.hapi.node.state.roster.RosterEntry;
import com.hedera.node.app.hapi.utils.EntityType;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import com.hedera.node.app.spi.ids.ReadableEntityCounters;
import com.swirlds.state.spi.ReadableKVState;
import com.swirlds.state.spi.ReadableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.LongUnaryOperator;

/**
 * Provides read-only methods for interacting with the underlying data storage mechanisms for
 * working with Nodes.
 *
 * <p>This class is not exported from the module. It is an internal implementation detail.
 */
public class ReadableNodeStoreImpl implements ReadableNodeStore {
    /**
     * The underlying data storage class that holds the node data.
     */
    private final ReadableKVState<EntityNumber, Node> nodesState;

    private final ReadableEntityCounters entityCounters;

    /**
     * Create a new {@link ReadableNodeStoreImpl} instance.
     *
     * @param states The state to use.
     */
    public ReadableNodeStoreImpl(
            @NonNull final ReadableStates states, @NonNull final ReadableEntityCounters entityCounters) {
        requireNonNull(states);
        this.entityCounters = requireNonNull(entityCounters);
        this.nodesState = states.get(NODES_STATE_ID);
    }

    @Override
    public Roster snapshotOfFutureRoster(@NonNull final LongUnaryOperator weightFunction) {
        requireNonNull(weightFunction);
        return constructFromNodesStateWithStakingInfoWeight(this, weightFunction);
    }

    /**
     * Returns the node needed.
     *
     * @param nodeId node id being looked up
     * @return node
     */
    @Override
    @Nullable
    public Node get(final long nodeId) {
        return nodesState.get(EntityNumber.newBuilder().number(nodeId).build());
    }

    /**
     * Returns the number of topics in the state.
     *
     * @return the number of topics in the state
     */
    public long sizeOfState() {
        return entityCounters.getCounterFor(EntityType.NODE);
    }

    protected <T extends ReadableKVState<EntityNumber, Node>> T nodesState() {
        return (T) nodesState;
    }

    @NonNull
    public List<EntityNumber> keys() {
        final var size = sizeOfState();
        final var keys = new ArrayList<EntityNumber>();
        for (int i = 0; i < size; i++) {
            final var key = new EntityNumber(i);
            final var node = nodesState.get(key);
            if (node != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    private Roster constructFromNodesStateWithStakingInfoWeight(
            @NonNull final ReadableNodeStoreImpl nodeStore, @NonNull final LongUnaryOperator weightFunction) {
        final var rosterEntries = new ArrayList<RosterEntry>();
        for (final var nodeNumber : nodeStore.keys()) {
            final var node = requireNonNull(nodeStore.get(nodeNumber.number()));
            var nodeEndpoints = node.gossipEndpoint();
            // we want to swap the internal and external node endpoints
            // so that the external one is at index 0
            if (nodeEndpoints.size() > 1) {
                nodeEndpoints = List.of(nodeEndpoints.getLast(), nodeEndpoints.getFirst());
            }
            if (!node.deleted()) {
                final var entry = RosterEntry.newBuilder()
                        .nodeId(node.nodeId())
                        .weight(weightFunction.applyAsLong(node.nodeId()))
                        .gossipCaCertificate(node.gossipCaCertificate())
                        .gossipEndpoint(nodeEndpoints)
                        .build();
                rosterEntries.add(entry);
            }
        }
        rosterEntries.sort(Comparator.comparingLong(RosterEntry::nodeId));
        return Roster.newBuilder().rosterEntries(rosterEntries).build();
    }
}
