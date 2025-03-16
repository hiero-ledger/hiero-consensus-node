// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.addressbook.impl.schemas;

import static com.hedera.node.app.service.addressbook.impl.schemas.V053AddressBookSchema.NODES_KEY;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.hapi.node.state.common.EntityNumber;
import com.hedera.node.app.service.addressbook.AddressBookService;
import com.hedera.node.internal.network.Network;
import com.hedera.node.internal.network.NodeMetadata;
import com.swirlds.state.lifecycle.MigrationContext;
import com.swirlds.state.lifecycle.Schema;
import com.swirlds.state.spi.WritableKVState;
import com.swirlds.state.spi.WritableStates;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The {@link Schema#restart(MigrationContext)} implementation whereby the {@link AddressBookService} ensures that any
 * node metadata overrides in the startup assets are copied into the state.
 * <p>
 * <b>Important:</b> The latest {@link AddressBookService} schema should always implement this interface.
 */
public interface AddressBookTransplantSchema {
    Logger log = LogManager.getLogger(AddressBookTransplantSchema.class);

    default void restart(@NonNull final MigrationContext ctx) {
        requireNonNull(ctx);
        ctx.startupNetworks()
                .overrideNetworkFor(ctx.roundNumber(), ctx.platformConfig())
                .ifPresent(network -> {
                    final var count = setNodeMetadata(network, ctx.newStates());
                    log.info("Adopted {} node metadata entries from startup assets", count);
                });
    }

    /**
     * Set the node metadata in the state from the provided network, for whatever nodes are available.
     *
     * @param network the network from which to extract the node metadata
     * @param writableStates the state in which to store the node metadata
     */
    default int setNodeMetadata(@NonNull final Network network, @NonNull final WritableStates writableStates) {
        final WritableKVState<EntityNumber, Node> nodes = writableStates.get(NODES_KEY);
        final var adoptedNodeCount = new AtomicInteger();

        network.nodeMetadata().stream()
                .filter(NodeMetadata::hasNode)
                .map(NodeMetadata::nodeOrThrow)
                .forEach(node -> {
                    // We need to check if the node definition is already in the state. If not, insert it; otherwise,
                    // skip the insertion, because a restart will cause the entity number for _only_ this node to
                    // increase, resulting in an ISS
                    final var maybeStateNode = nodes.get(
                            EntityNumber.newBuilder().number(node.nodeId()).build());
                    if (!equalExceptAdminKey(node, maybeStateNode)) {
                        log.info("Node {} has changed definition from {} to {}", node.nodeId(), maybeStateNode, node);
                        adoptedNodeCount.getAndIncrement();
                        nodes.put(new EntityNumber(node.nodeId()), node);
                    } else {
                        log.info("Network node {} found in current nodes", node.nodeId());
                    }
                });
        return adoptedNodeCount.get();
    }

    static boolean equalExceptAdminKey(final Node node1, final Node node2) {
        if (node1 == node2) return true;
        if (node1 == null || node2 == null) return false;

        return node1.nodeId() == node2.nodeId()
                && Objects.equals(node1.accountId(), node2.accountId())
                && Objects.equals(node1.description(), node2.description())
                && Objects.equals(node1.gossipEndpoint(), node2.gossipEndpoint())
                && Objects.equals(node1.serviceEndpoint(), node2.serviceEndpoint())
                && Objects.equals(node1.gossipCaCertificate(), node2.gossipCaCertificate())
                && Objects.equals(node1.grpcCertificateHash(), node2.grpcCertificateHash())
                && node1.weight() == node2.weight()
                && node1.deleted() == node2.deleted();
    }
}
