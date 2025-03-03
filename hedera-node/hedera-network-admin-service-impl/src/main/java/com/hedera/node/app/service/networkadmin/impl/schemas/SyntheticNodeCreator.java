// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.networkadmin.impl.schemas;

import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.state.addressbook.Node;
import com.hedera.node.app.service.addressbook.ReadableNodeStore;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Comparator;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class generates synthetic records for all nodes created in state during genesis.
 */
@Singleton
public class SyntheticNodeCreator {
    private static final Comparator<Node> NODE_COMPARATOR = Comparator.comparing(Node::nodeId, Long::compare);

    /**
     * Create a new instance.
     */
    @Inject
    public SyntheticNodeCreator() {}

    public void generateSyntheticNodes(
            @NonNull final ReadableNodeStore readableNodeStore,
            @NonNull final Consumer<SortedSet<Node>> nodesConsumer) {
        requireNonNull(readableNodeStore);
        requireNonNull(nodesConsumer);

        final var nodes = new TreeSet<>(NODE_COMPARATOR);
        final var iter = readableNodeStore.keys();
        while (iter.hasNext()) {
            final var node = readableNodeStore.get(iter.next().number());
            if (node != null) {
                nodes.add(node);
            }
        }

        nodesConsumer.accept(nodes);
    }
}
