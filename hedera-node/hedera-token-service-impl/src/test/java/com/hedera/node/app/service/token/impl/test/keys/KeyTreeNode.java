// SPDX-License-Identifier: Apache-2.0
package com.hedera.node.app.service.token.impl.test.keys;

import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

import com.hederahashgraph.api.proto.java.Key;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

public interface KeyTreeNode {
    static KeyTreeNode from(NodeFactory factory) {
        if (factory == LeafFactory.DEFAULT_FACTORY) {
            return new KeyTreeLeaf();
        } else if (factory instanceof LeafFactory) {
            LeafFactory typedFactory = (LeafFactory) factory;
            return new KeyTreeLeaf(typedFactory.isUsedToSign(), typedFactory.getLabel(), typedFactory.getSigType());
        } else if (factory instanceof ThresholdFactory) {
            ThresholdFactory typedFactory = (ThresholdFactory) factory;
            List<KeyTreeNode> children =
                    typedFactory.childFactories.stream().collect(mapping(KeyTreeNode::from, toList()));
            return new KeyTreeThresholdNode(children, typedFactory.M);
        } else if (factory instanceof ListFactory) {
            ListFactory typedFactory = (ListFactory) factory;
            List<KeyTreeNode> children =
                    typedFactory.childFactories.stream().collect(mapping(KeyTreeNode::from, toList()));
            return new KeyTreeListNode(children);
        }
        throw new AssertionError("Impossible factory implementation: " + factory.getClass());
    }

    int numLeaves();

    Key asKey(KeyFactory factory);

    void traverse(Predicate<KeyTreeNode> shouldVisit, Consumer<KeyTreeNode> visitor);

    default Key asKey() {
        return asKey(KeyFactory.getDefaultInstance());
    }
}
