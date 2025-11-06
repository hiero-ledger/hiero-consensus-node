// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.info;

import static com.hedera.node.app.info.NodeInfoImpl.fromRosterEntry;
import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_STATE_ID;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.AccountID;
import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.hapi.node.state.entity.EntityCounts;
import com.hedera.hapi.node.state.roster.Roster;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.app.service.entityid.EntityIdService;
import com.hedera.node.app.service.entityid.impl.schemas.V0590EntityIdSchema;
import com.hedera.node.app.spi.info.NetworkInfo;
import com.hedera.node.app.spi.info.NodeInfo;
import com.hedera.node.config.ConfigProvider;
import com.hedera.node.config.data.HederaConfig;
import com.hedera.node.config.data.LedgerConfig;
import com.hedera.node.internal.network.Network;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.config.api.Configuration;
import com.swirlds.state.State;
import com.swirlds.state.spi.ReadableKVState;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.inject.Singleton;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Provides information about the network, including the ledger ID, which may be
 * overridden by configuration in state and cannot be used during state migrations
 * that precede loading configuration sources from state.
 */
@Singleton
public class StateNetworkInfo implements NetworkInfo {

    private static final Logger log = LogManager.getLogger(StateNetworkInfo.class);
    private final long selfId;
    private final Bytes ledgerId;
    /**
     * The active roster, used to limit exposed node info to the active set of nodes.
     */
    private final Roster activeRoster;

    /**
     * A supplier for the genesis network, used to populate the node info map when the
     * state is empty.
     */
    private final Supplier<Network> genesisNetworkSupplier;

    /**
     * Non-final because we need {@code handleTransaction} to be able to swap in an
     * updated map atomically, without giving a pre-handle thread a temporary view of
     * an empty map. (Note that {@code handleTransaction}'s updates will only change
     * <i>metadata</i> of nodes, but not the set of nodes itself; so pre-handle threads
     * can use any version of the map to test for address book membership.)
     */
    private volatile Map<Long, NodeInfo> nodeInfos;

    private final Configuration configuration;

    /**
     * Constructs a new network information provider from the given state, roster, selfID, and configuration provider.
     *
     * @param selfId the ID of the node
     * @param state the state to retrieve the network information from
     * @param roster the roster to retrieve the network information from
     * @param configProvider the configuration provider to retrieve the ledger ID from
     * @param genesisNetworkSupplier the supplier for the genesis network
     */
    public StateNetworkInfo(
            final long selfId,
            @NonNull final State state,
            @NonNull final Roster roster,
            @NonNull final ConfigProvider configProvider,
            @NonNull final Supplier<Network> genesisNetworkSupplier) {
        requireNonNull(state);
        requireNonNull(configProvider);
        this.activeRoster = requireNonNull(roster);
        this.genesisNetworkSupplier = requireNonNull(genesisNetworkSupplier);
        this.configuration = configProvider.getConfiguration();
        this.ledgerId = configProvider
                .getConfiguration()
                .getConfigData(LedgerConfig.class)
                .id();
        this.nodeInfos = nodeInfosFrom(state);
        this.selfId = selfId;
    }

    @NonNull
    @Override
    public Bytes ledgerId() {
        return ledgerId;
    }

    @NonNull
    @Override
    public NodeInfo selfNodeInfo() {
        return nodeInfos.get(selfId);
    }

    @NonNull
    @Override
    public List<NodeInfo> addressBook() {
        return List.copyOf(nodeInfos.values());
    }

    @Nullable
    @Override
    public NodeInfo nodeInfo(final long nodeId) {
        return nodeInfos.get(nodeId);
    }

    @Override
    public boolean containsNode(final long nodeId) {
        return nodeInfos.containsKey(nodeId);
    }

    @Override
    public void updateFrom(@NonNull final State state) {
        nodeInfos = nodeInfosFrom(state);
    }

    /**
     * Build a map of node information from the state. The map is keyed by node ID.
     * The node information is retrieved from the address book service. If the node
     * information is not found in the address book service, it is not included in the map.
     *
     * @param state the state to retrieve the node information from
     * @return a map of node information
     */
    private Map<Long, NodeInfo> nodeInfosFrom(@NonNull final State state) {
        final var entityCounts = state.getReadableStates(EntityIdService.NAME)
                .<EntityCounts>getSingleton(V0590EntityIdSchema.ENTITY_COUNTS_STATE_ID);
        final var nodeInfos = new LinkedHashMap<Long, NodeInfo>();
        if (requireNonNull(entityCounts.get()).numNodes() == 0) {
            // If there are no nodes in state, we can only fall back to the genesis network assets
            // until the first round is handled and the system entities created; c.f. doGenesisSetup()
            // in SystemTransactions which will give us another chance to populate from state then
            final var network = genesisNetworkSupplier.get();
            for (final var metadata : network.nodeMetadata()) {
                final var node = metadata.nodeOrThrow();
                final var nodeInfo = new NodeInfoImpl(
                        node.nodeId(),
                        node.accountIdOrThrow(),
                        node.weight(),
                        node.gossipEndpoint(),
                        node.gossipCaCertificate(),
                        node.serviceEndpoint(),
                        node.declineReward(),
                        node.grpcCertificateHash().length() > 0 ? node.grpcCertificateHash() : null);
                nodeInfos.put(node.nodeId(), nodeInfo);
            }
        } else {
            final ReadableKVState<EntityNumber, Node> nodes =
                    state.getReadableStates(AddressBookService.NAME).get(NODES_STATE_ID);
            final var hederaConfig = configuration.getConfigData(HederaConfig.class);
            for (final var rosterEntry : activeRoster.rosterEntries()) {
                // At genesis the node store is derived from the roster, hence must have info for every
                // node id; and from then on, the roster is derived from the node store, and hence the
                // node store must have every node id in the roster.
                final var node = nodes.get(new EntityNumber(rosterEntry.nodeId()));
                if (node != null) {
                    // Notice it's possible the node could be deleted here, because a DAB transaction removed
                    // it from the future address book; that doesn't mean we should stop using it in the current
                    // version of the software
                    nodeInfos.put(rosterEntry.nodeId(), NodeInfoImpl.fromRosterWithCurrentMetadata(rosterEntry, node));
                } else {
                    nodeInfos.put(
                            rosterEntry.nodeId(),
                            fromRosterEntry(
                                    rosterEntry,
                                    AccountID.newBuilder()
                                            .shardNum(hederaConfig.shard())
                                            .realmNum(hederaConfig.realm())
                                            .accountNum(rosterEntry.nodeId() + 3)
                                            .build()));
                    log.error("Roster includes a node {} that is not found in node store", rosterEntry.nodeId());
                }
            }
        }
        return Collections.unmodifiableMap(nodeInfos);
    }
}
