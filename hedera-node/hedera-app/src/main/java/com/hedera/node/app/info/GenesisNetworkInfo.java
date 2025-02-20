// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.info;

import static java.util.Objects.requireNonNull;

import com.hedera.node.internal.network.Network;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import com.swirlds.state.State;
import com.swirlds.state.lifecycle.info.NetworkInfo;
import com.swirlds.state.lifecycle.info.NodeInfo;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides information about the network based on the given roster and ledger ID.
 */
public class GenesisNetworkInfo implements NetworkInfo {
    private final Bytes ledgerId;
    private final Map<Long, NodeInfo> nodeInfos;

    /**
     * Constructs a new {@link GenesisNetworkInfo} instance.
     *
     * @param genesisNetwork The genesis network
     * @param ledgerId      The ledger ID
     */
    public GenesisNetworkInfo(@NonNull final Network genesisNetwork, @NonNull final Bytes ledgerId) {
        this.ledgerId = requireNonNull(ledgerId);
        this.nodeInfos = nodeInfosFrom(genesisNetwork);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public Bytes ledgerId() {
        return ledgerId;
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public NodeInfo selfNodeInfo() {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public List<NodeInfo> addressBook() {
        return List.copyOf(nodeInfos.values());
    }

    /**
     * {@inheritDoc}
     */
    @Nullable
    @Override
    public NodeInfo nodeInfo(final long nodeId) {
        return nodeInfos.get(nodeId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsNode(final long nodeId) {
        return nodeInfos.containsKey(nodeId);
    }

    @Override
    public void updateFrom(final State state) {
        throw new UnsupportedOperationException("Not implemented");
    }

    private static Map<Long, NodeInfo> nodeInfosFrom(@NonNull final Network network) {
        final var nodeInfos = new LinkedHashMap<Long, NodeInfo>();
        for (final var metadata : network.nodeMetadata()) {
            final var node = metadata.nodeOrThrow();
            final var nodeInfo = new NodeInfoImpl(
                    node.nodeId(),
                    node.accountIdOrThrow(),
                    node.weight(),
                    node.gossipEndpoint(),
                    node.gossipCaCertificate());
            nodeInfos.put(node.nodeId(), nodeInfo);
        }
        return nodeInfos;
    }
}
